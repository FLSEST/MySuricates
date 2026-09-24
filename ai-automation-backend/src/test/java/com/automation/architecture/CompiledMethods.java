package com.automation.architecture;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The methods of compiled classes as far as {@link TransactionalCallScan} needs them, read with the
 * ASM copy that ships inside spring-core (no extra dependency): every call a method body makes –
 * lambda and method reference targets included –, the propagation of a {@code @Transactional} on the
 * method or its class, and the lambdas a method hands to {@code TransactionTemplate.execute}.
 */
final class CompiledMethods {

    static final String TRANSACTIONAL = "Lorg/springframework/transaction/annotation/Transactional;";
    static final String JTA_TRANSACTIONAL = "Ljakarta/transaction/Transactional;";

    private static final int API = SpringAsmInfo.ASM_VERSION;
    private static final String REQUIRED = "REQUIRED";
    private static final Set<String> TEMPLATE_OWNERS = Set.of(
            "org/springframework/transaction/support/TransactionTemplate",
            "org/springframework/transaction/support/TransactionOperations");
    private static final Set<String> TEMPLATE_METHODS = Set.of("execute", "executeWithoutResult");
    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";

    private CompiledMethods() {
    }

    /** A call site target in internal names ({@code com/automation/Foo}). */
    record MethodRef(String owner, String name, String desc) {
    }

    static final class ClassInfo {
        final String name;
        final String superName;
        final List<String> interfaces;
        /** Propagation of a class-level {@code @Transactional}; null when the class carries none. */
        String propagation;
        /** Keyed by name + descriptor. */
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

    /** {@code type} is {@code target} or inherits from it within the read classes. */
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
