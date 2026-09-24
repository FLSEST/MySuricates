package com.automation.testsupport;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Spring's transaction machinery for plain unit tests, without a database.
 *
 * <p>{@link #manager()} opens transactions that count as actual – inside one,
 * {@link TransactionSynchronizationManager#isActualTransactionActive()} is true as with the real
 * JPA transaction manager, and an inner transaction joins an outer one. {@link #proxy} wraps a
 * service the way the application context does, so its {@code @Transactional} annotations take
 * effect. Together they let a unit test ask from inside a mocked repository or CLI call whether a
 * transaction is open – the check behind the pool-size-1 rule "no AI/CLI call inside a transaction".
 */
public final class FakeTransactions {

    private FakeTransactions() {
    }

    public static PlatformTransactionManager manager() {
        return new Manager();
    }

    /** {@code service} behind a proxy that applies its {@code @Transactional} annotations. */
    @SuppressWarnings("unchecked")
    public static <T> T proxy(T service, PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource(false)));
        return (T) factory.getProxy();
    }

    /** Whether the calling code runs inside a transaction right now. */
    public static boolean active() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    private static final class Manager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return active();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return (Boolean) transaction;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // nothing to open – the synchronization of the base class marks the transaction as actual
        }

        @Override
        protected Object doSuspend(Object transaction) {
            return null;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            // nothing was suspended
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            // a failing inner participant marks nothing – there is no resource to roll back
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no resource
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no resource
        }
    }
}
