package com.automation.architecture;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Which calls run inside a Spring-managed transaction, read from the compiled bytecode with the ASM
 * copy that ships inside spring-core (no extra dependency).
 *
 * <p>A <em>transactional root</em> is
 * <ul>
 *   <li>a non-private, non-static method annotated with {@code @Transactional}, or such a method of a
 *       class annotated with it (the method annotation wins) – propagation {@code NOT_SUPPORTED} and
 *       {@code NEVER} run without a transaction and are no roots;</li>
 *   <li>the callback handed to {@code TransactionTemplate.execute}/{@code executeWithoutResult} – a
 *       lambda or method reference of the same class.</li>
 * </ul>
 *
 * <p>From each root the scan follows every call that stays on the same object – self calls, private
 * helpers, lambda bodies, method references, methods inherited from scanned superclasses – because
 * those run inside the same transaction without passing a proxy. Each reached call the caller's
 * predicate marks as slow is one {@link Finding}.
 *
 * <p>Blind spots, on purpose: calls into other beans are not followed (a transactional method that
 * asks another service to call the model stays invisible), nor anonymous inner classes, reflection
 * or annotations on interfaces. The scan pins the direct shape; it is not a call-graph proof. The
 * one over-approximation: a lambda counts wherever it runs, so one handed to an executor is reported
 * as if it ran inside the transaction.
 */
final class TransactionalCallScan {

    static final String TRANSACTIONAL = "Lorg/springframework/transaction/annotation/Transactional;";
    static final String JTA_TRANSACTIONAL = "Ljakarta/transaction/Transactional;";

    private static final int API = SpringAsmInfo.ASM_VERSION;
    private static final String REQUIRED = "REQUIRED";
    private static final Set<String> WITHOUT_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER");
    private static final Set<String> TEMPLATE_OWNERS = Set.of(
            "org/springframework/transaction/support/TransactionTemplate",
            "org/springframework/transaction/support/TransactionOperations");
    private static final Set<String> TEMPLATE_METHODS = Set.of("execute", "executeWithoutResult");
    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";

    private TransactionalCallScan() {
    }

    /** A call site target in internal names ({@code com/automation/Foo}). */
    record MethodRef(String owner, String name, String desc) {
    }

    /** {@code "<root class>#<root method> -> <slow class simple name>#<slow method>"}. */
    record Finding(String root, String call) implements Comparable<Finding> {

        @Override
        public String toString() {
            return root + " -> " + call;
        }

        @Override
        public int compareTo(Finding other) {
            return toString().compareTo(other.toString());
        }
    }

    static final class ClassInfo {
        final String name;
        final String superName;
        final List<String> interfaces;
        /** Propagation of a class-level {@code @Transactional}; null when the class carries none. */
        String propagation;
        final Map<String, MethodInfo> methods = new LinkedHashMap<>();

        ClassInfo(String name, String superName, List<String> interfaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
        }
    }

    static final class MethodInfo {
        final int access;
        final String name;
        /** Propagation of a method-level {@code @Transactional}; null when the method carries none. */
        String propagation;
        final List<MethodRef> calls = new ArrayList<>();
        final List<MethodRef> templateCallbacks = new ArrayList<>();

        MethodInfo(int access, String name) {
            this.access = access;
            this.name = name;
        }
    }

    /** All classes below {@code classes}, keyed by internal name. */
    static Map<String, ClassInfo> readDirectory(Path classes) throws IOException {
        Map<String, ClassInfo> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassInfo info = read(Files.readAllBytes(file));
                result.put(info.name, info);
            }
        }
        return result;
    }

    static ClassInfo read(byte[] bytecode) {
        ClassInfo[] read = new ClassInfo[1];
        new ClassReader(bytecode).accept(new ClassVisitor(API) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                read[0] = new ClassInfo(name, superName, interfaces == null ? List.of() : List.of(interfaces));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return transactional(descriptor, propagation -> read[0].propagation = propagation);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                MethodInfo method = new MethodInfo(access, name);
                read[0].methods.put(name + descriptor, method);
                return new CallCollector(method);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return read[0];
    }

    /** Every slow call reached from a transactional root, sorted. */
    static Set<Finding> findings(Map<String, ClassInfo> classes, Predicate<MethodRef> slow) {
        Set<Finding> found = new TreeSet<>();
        for (ClassInfo type : classes.values()) {
            for (MethodInfo method : type.methods.values()) {
                String root = type.name.replace('/', '.') + "#" + method.name;
                if (isRoot(type, method, classes)) {
                    walk(type, method, root, classes, slow, found);
                }
                for (MethodRef callback : method.templateCallbacks) {
                    MethodInfo body = resolve(callback, type, classes);
                    if (body != null) {
                        walk(type, body, root + " (TransactionTemplate)", classes, slow, found);
                    }
                }
            }
        }
        return found;
    }

    /** Number of transactional roots – a scan that finds none measured nothing. */
    static long countRoots(Map<String, ClassInfo> classes) {
        return classes.values().stream()
                .mapToLong(type -> type.methods.values().stream().filter(m -> isRoot(type, m, classes)).count())
                .sum();
    }

    /** {@code type} is {@code target} or inherits from it within the scanned classes. */
    static boolean isSubtype(String type, String target, Map<String, ClassInfo> classes) {
        Deque<String> open = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!open.isEmpty()) {
            String current = open.poll();
            if (current.equals(target)) {
                return true;
            }
            ClassInfo info = classes.get(current);
            if (info != null && seen.add(current)) {
                if (info.superName != null) {
                    open.add(info.superName);
                }
                open.addAll(info.interfaces);
            }
        }
        return false;
    }

    private static boolean isRoot(ClassInfo type, MethodInfo method, Map<String, ClassInfo> classes) {
        int excluded = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;
        if ((method.access & excluded) != 0 || method.name.startsWith("<")) {
            return false;
        }
        String propagation = method.propagation != null ? method.propagation : classPropagation(type, classes);
        return propagation != null && !WITHOUT_TRANSACTION.contains(propagation);
    }

    /** {@code @Transactional} is {@code @Inherited}: the nearest annotated class of the chain counts. */
    private static String classPropagation(ClassInfo type, Map<String, ClassInfo> classes) {
        for (ClassInfo current = type; current != null;
             current = current.superName == null ? null : classes.get(current.superName)) {
            if (current.propagation != null) {
                return current.propagation;
            }
        }
        return null;
    }

    private static void walk(ClassInfo type, MethodInfo start, String root, Map<String, ClassInfo> classes,
                             Predicate<MethodRef> slow, Set<Finding> found) {
        Deque<MethodInfo> open = new ArrayDeque<>(List.of(start));
        Set<MethodInfo> seen = new HashSet<>();
        while (!open.isEmpty()) {
            MethodInfo current = open.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (MethodRef call : current.calls) {
                if (slow.test(call)) {
                    found.add(new Finding(root, simpleName(call.owner()) + "#" + call.name()));
                    continue;
                }
                MethodInfo next = resolve(call, type, classes);
                if (next != null) {
                    open.add(next);
                }
            }
        }
    }

    /** The method a call on the same object lands on, or null for calls that leave the object. */
    private static MethodInfo resolve(MethodRef call, ClassInfo type, Map<String, ClassInfo> classes) {
        if (!isSubtype(type.name, call.owner(), classes)) {
            return null;
        }
        for (ClassInfo current = classes.get(call.owner()); current != null;
             current = current.superName == null ? null : classes.get(current.superName)) {
            MethodInfo method = current.methods.get(call.name() + call.desc());
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static String simpleName(String internalName) {
        String name = internalName.substring(internalName.lastIndexOf('/') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }

    /**
     * Reads a {@code @Transactional}: Spring's {@code propagation} or JTA's {@code value}, REQUIRED
     * when absent. Returns null for every other annotation, so ASM skips it.
     */
    private static AnnotationVisitor transactional(String descriptor, Consumer<String> propagation) {
        if (!TRANSACTIONAL.equals(descriptor) && !JTA_TRANSACTIONAL.equals(descriptor)) {
            return null;
        }
        propagation.accept(REQUIRED);
        return new AnnotationVisitor(API) {
            @Override
            public void visitEnum(String name, String enumDescriptor, String value) {
                if ("propagation".equals(name) || "value".equals(name)) {
                    propagation.accept(value);
                }
            }
        };
    }

    /**
     * Collects the calls of one method body. A lambda or method reference that is created right before
     * {@code TransactionTemplate.execute} is that call's callback – the template takes it as its last
     * argument, so no other call can sit in between.
     */
    private static final class CallCollector extends MethodVisitor {

        private final MethodInfo method;
        private MethodRef pendingCallback;

        CallCollector(MethodInfo method) {
            super(API);
            this.method = method;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return transactional(descriptor, propagation -> method.propagation = propagation);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (pendingCallback != null && TEMPLATE_OWNERS.contains(owner) && TEMPLATE_METHODS.contains(name)) {
                method.templateCallbacks.add(pendingCallback);
            }
            pendingCallback = null;
            method.calls.add(new MethodRef(owner, name, descriptor));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            pendingCallback = null;
            if (LAMBDA_FACTORY.equals(bootstrap.getOwner()) && arguments.length > 1
                    && arguments[1] instanceof Handle target) {
                MethodRef reference = new MethodRef(target.getOwner(), target.getName(), target.getDesc());
                method.calls.add(reference);
                pendingCallback = reference;
            }
        }
    }
}
