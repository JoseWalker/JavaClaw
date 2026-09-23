package ai.javaclaw.chat.ui;

import org.springframework.beans.factory.ObjectProvider;

/**
 * The narrowest possible {@link ObjectProvider}: one bean, or none.
 *
 * <p>Components that must keep working when a bean is absent take an {@code ObjectProvider} rather
 * than the bean itself, and a test that wants to exercise the absent case has nothing to hand them.
 * This is that nothing, and the one-bean case beside it, so no test has to start a Spring context to
 * say which of the two it is testing.
 *
 * @param bean the single bean to hand out, or null to stand for a bean that is not configured
 */
record SoleBean<T>(T bean) implements ObjectProvider<T> {

    @Override
    public T getIfUnique() {
        return bean;
    }

    @Override
    public T getIfAvailable() {
        return bean;
    }

    /**
     * Unsupported on purpose. The contract of these two is to throw when the bean is missing, and a
     * test fixture that quietly returned null instead would let production code depend on a
     * behaviour Spring does not have.
     */
    @Override
    public T getObject() {
        throw new UnsupportedOperationException("Use getIfUnique");
    }

    @Override
    public T getObject(Object... args) {
        throw new UnsupportedOperationException("Use getIfUnique");
    }
}
