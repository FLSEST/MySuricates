package com.automation.architecture;

import com.automation.architecture.CompiledMethods.ClassInfo;
import com.automation.architecture.CompiledMethods.MethodInfo;
import com.automation.architecture.CompiledMethods.MethodRef;
import org.springframework.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Which calls run inside a Spring-managed transaction, measured on the {@link CompiledMethods} of
 * the compiled classes.
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

    private static final Set<String> WITHOUT_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER");

    private TransactionalCallScan() {
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
        if (!CompiledMethods.isSubtype(type.name, call.owner(), classes)) {
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
}
