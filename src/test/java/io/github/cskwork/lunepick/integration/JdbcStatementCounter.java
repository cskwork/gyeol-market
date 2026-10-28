package io.github.cskwork.lunepick.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Counts statements on the requesting test thread, excluding background workers. */
final class JdbcStatementCounter implements BeanPostProcessor {
    private static final ThreadLocal<Integer> COUNT = new ThreadLocal<>();

    static void start() {
        COUNT.set(0);
    }

    static int stop() {
        int count = COUNT.get();
        COUNT.remove();
        return count;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource)) return bean;
        return Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(bean, method, args);
                    if (!(result instanceof Connection connection)) return result;
                    return Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if (m.getName().startsWith("prepareStatement")
                                        && COUNT.get() != null) COUNT.set(COUNT.get() + 1);
                                return invoke(connection, m, a);
                            });
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
