package com.shareguard.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shareguard.backend.ShareGuardBackendApplication;
import com.shareguard.backend.auth.PortOneIdentityClient;
import com.shareguard.backend.auth.PortOneProperties;
import com.shareguard.backend.payment.HttpTossGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RuntimeHttpClientsTest {
    // Exercise the real Boot HTTP configuration and both production client constructors.
    // No database, actual API credentials, or external network calls are required.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            RestClientAutoConfiguration.class
        ))
        .withUserConfiguration(ClientsConfiguration.class)
        .withPropertyValues(
            "shareguard.portone.base-url=https://portone.example.test",
            "shareguard.portone.api-secret=test-only-secret",
            "shareguard.toss.base-url=https://toss.example.test",
            "shareguard.toss.secret-key="
        );

    @Test
    void createsBothGatewaysWithSeparateAutoConfiguredBuilders() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PortOneIdentityClient.class);
            assertThat(context).hasSingleBean(HttpTossGateway.class);
            assertThat(context.getBean(RestClient.Builder.class))
                .isNotSameAs(context.getBean(RestClient.Builder.class));
        });
    }

    @Test
    void productionApplicationContextStartsWithoutExternalDatabaseOrApiCalls() {
        new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ShareGuardBackendApplication.class)
            // Only the DB transport is replaced: keep the real HTTP clients and security wiring.
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withPropertyValues(
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "spring.flyway.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.test/jwks.json"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(PortOneIdentityClient.class);
                assertThat(context).hasSingleBean(HttpTossGateway.class);
                assertThat(context).hasSingleBean(SecurityFilterChain.class);
            });
    }

    @Test
    void autoConfiguredClientCanDecodePortOneJsonAndSendAuthorization() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var builder = context.getBean(RestClient.Builder.class);
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new PortOneIdentityClient(builder, context.getBean(PortOneProperties.class));
            var verificationId = "identity-verification-test";
            server.expect(requestTo("https://portone.example.test/identity-verifications/" + verificationId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "PortOne test-only-secret"))
                .andRespond(withSuccess("""
                    {
                      "id": "identity-verification-test",
                      "status": "VERIFIED",
                      "verifiedCustomer": {
                        "name": "Test User",
                        "phoneNumber": "010-1234-5678",
                        "birthDate": "1995-01-01",
                        "ci": "test-ci",
                        "di": "test-di"
                      }
                    }
                    """, MediaType.APPLICATION_JSON));

            var identity = client.verify(verificationId);

            assertThat(identity.identityVerificationId()).isEqualTo(verificationId);
            assertThat(identity.phoneNumber()).isEqualTo("01012345678");
            server.verify();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PortOneProperties.class)
    @Import({PortOneIdentityClient.class, HttpTossGateway.class})
    static class ClientsConfiguration {}
}
