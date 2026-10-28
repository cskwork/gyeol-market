package io.github.cskwork.lunepick.integration;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Finish native database cleanup before Surefire starts its JVM shutdown deadline. */
final class EmbeddedDatabaseLifecycle implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot()
                .getStore(ExtensionContext.Namespace.create(EmbeddedDatabaseLifecycle.class))
                .getOrComputeIfAbsent(
                        "postgres",
                        key ->
                                (ExtensionContext.Store.CloseableResource)
                                        EmbeddedDatabase::stopPostgres);
    }
}
