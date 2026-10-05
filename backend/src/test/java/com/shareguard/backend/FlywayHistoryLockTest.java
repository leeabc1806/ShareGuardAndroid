package com.shareguard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Reproduces the Render V7 lock failure only in the disposable loopback database. */
@EnabledIfEnvironmentVariable(named = "SHAREGUARD_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55432/shareguard")
class FlywayHistoryLockTest {
    private static final String SCHEMA = "flyway_lock_regression";

    @Test
    void sessionLockAllowsHistoryRlsMigrationAndSubsequentStartup() {
        var source = new DriverManagerDataSource(System.getenv("SHAREGUARD_TEST_DB_URL"), "postgres",
            System.getenv("SHAREGUARD_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        try {
            // The default transaction-level lock reproduces the production
            // failure: its history-table reader blocks the migration connection.
            var failure = assertThrows(FlywayException.class, () -> configuration(source).load().migrate());
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertInstanceOf(SQLException.class, cause);
            assertEquals("57014", ((SQLException) cause).getSQLState());

            var config = configuration(source);
            config.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
            var flyway = config.load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            flyway.validate();
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertTrue(jdbc.queryForObject(
                "select relrowsecurity from pg_class where oid = 'flyway_lock_regression.flyway_schema_history'::regclass",
                Boolean.class));
            assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_lock_regression.flyway_schema_history where version = '1' and success", Integer.class));
        } finally {
            jdbc.execute("drop schema if exists flyway_lock_regression cascade");
        }
    }

    private FluentConfiguration configuration(DriverManagerDataSource source) {
        return Flyway.configure().dataSource(source).schemas(SCHEMA).defaultSchema(SCHEMA)
            .locations("classpath:db/lock-regression")
            .initSql("set statement_timeout = '2s'")
            .javaMigrations(new V1__EnableHistoryRls());
    }

    public static class V1__EnableHistoryRls extends BaseJavaMigration {
        @Override
        public void migrate(Context context) throws Exception {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute("alter table flyway_lock_regression.flyway_schema_history enable row level security");
            }
        }
    }
}
