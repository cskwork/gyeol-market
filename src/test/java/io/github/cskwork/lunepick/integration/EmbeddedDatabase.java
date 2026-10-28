package io.github.cskwork.lunepick.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One disposable, real PostgreSQL process per test JVM; no external database or container. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(EmbeddedDatabaseLifecycle.class)
public abstract class EmbeddedDatabase {
    private static final EmbeddedPostgres POSTGRES = start();

    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    private static EmbeddedPostgres start() {
        try {
            var database = EmbeddedPostgres.builder().setPort(0).start();
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        try {
                                            database.close();
                                        } catch (IOException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    },
                                    "test-postgres-stop"));
            return database;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    protected static void databaseProperties(DynamicPropertyRegistry registry) {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.getPostgresDatabase().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot create isolated integration database", failure);
        }
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", name));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }
}
