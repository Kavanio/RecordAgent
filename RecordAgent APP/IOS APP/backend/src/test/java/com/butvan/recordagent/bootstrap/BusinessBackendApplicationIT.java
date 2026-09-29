package com.butvan.recordagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 以隔离的真实 PostgreSQL 校验启动迁移及最小 HTTP 暴露面。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessBackendApplicationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("test.postgres.image", "postgres:17.11"))
            .withDatabaseName("recordagent_test")
            .withUsername("recordagent_test")
            .withPassword(java.util.UUID.randomUUID().toString());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @Test
    void migratesFreshDatabaseAndExposesOnlyHealth() throws Exception {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(migrationCount).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'record_agent'", Integer.class))
                .isEqualTo(1);
        assertThat(restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> unauthorized = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/me", String.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String requestId = unauthorized.getHeaders().getFirst(RequestIdFilter.HEADER_NAME);
        assertThat(requestId).isNotBlank();
        JsonNode error = objectMapper.readTree(unauthorized.getBody());
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(error.path("requestId").asText()).isEqualTo(requestId);
    }
}
