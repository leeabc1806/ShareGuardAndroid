package com.shareguard.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "SHAREGUARD_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55432/shareguard")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.url=${SHAREGUARD_TEST_DB_URL}",
    "spring.datasource.username=postgres",
    "spring.datasource.password=${SHAREGUARD_TEST_DB_PASSWORD}",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/test-jwks"
})
class LocalServerStartupTest {
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void serverStartsWithDatabaseHealthAndAllMigrations() throws Exception {
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
        assertFalse(flyway.getConfiguration()
            .getConfigurationExtension(PostgreSQLConfigurationExtension.class).isTransactionalLock());
        assertEquals(4, jdbc.queryForObject("select count(*) from public.flyway_schema_history where version in ('1','2','3','4') and success", Integer.class));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from public.flyway_schema_history where version = '7' and success", Integer.class));
    }

    @Test
    void qrApiRequiresLoginEvenWhenServerIsHealthy() throws Exception {
        var response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/qr/issue"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }
}
