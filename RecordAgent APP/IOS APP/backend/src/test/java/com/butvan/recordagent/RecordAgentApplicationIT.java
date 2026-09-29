package com.butvan.recordagent;

import com.butvan.recordagent.filter.RequestIdFilter;
import static org.assertj.core.api.Assertions.assertThat;

import com.butvan.recordagent.repository.mapper.auth.AccountRowMappers;
import com.butvan.recordagent.repository.mapper.calendar.CalendarRowMappers;
import com.butvan.recordagent.repository.mapper.file.FileRowMappers;
import com.butvan.recordagent.repository.mapper.finance.FinanceRowMappers;
import com.butvan.recordagent.repository.mapper.record.RecordRowMappers;
import com.butvan.recordagent.repository.mapper.study.StudyRowMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 以隔离的真实 PostgreSQL 校验启动迁移及最小 HTTP 暴露面。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecordAgentApplicationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getProperty("test.postgres.image", "postgres:17.11"))
            .withDatabaseName("record")
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
        assertThat(migrationCount).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'record'", Integer.class))
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

    @Test
    void rejectsCrossUserBindingsAndInvalidDomainStates() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        insertUser(alice);
        insertUser(bob);

        UUID recordId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.record_entry(id, owner_id, record_date, record_type)
                VALUES (?, ?, current_date, 'journal')
                """, recordId, alice);
        UUID bobTag = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.record_tag(id, owner_id, name) VALUES (?, ?, 'tag')
                """, bobTag, bob);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO record.record_entry_tag(owner_id, record_id, tag_id)
                VALUES (?, ?, ?)
                """, alice, recordId, bobTag))).isInstanceOf(DataIntegrityViolationException.class);

        UUID bobFile = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.file_asset
                    (id, owner_id, original_name, media_type, size_bytes, backend_type, storage_key, status)
                VALUES (?, ?, 'photo.png', 'image/png', 1, 'local', ?, 'available')
                """, bobFile, bob, UUID.randomUUID().toString());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO record.record_file(owner_id, record_id, file_id)
                VALUES (?, ?, ?)
                """, alice, recordId, bobFile))).isInstanceOf(DataIntegrityViolationException.class);

        UUID accountId = UUID.randomUUID();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO record.finance_account
                    (id, owner_id, name, account_type, currency, balance_minor)
                VALUES (?, ?, 'wallet', 'cash', 'CNY', -1)
                """, accountId, alice))).isInstanceOf(DataIntegrityViolationException.class);

        UUID firstSession = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.study_session(id, owner_id, title, started_at, timezone)
                VALUES (?, ?, 'reading', now(), 'Asia/Shanghai')
                """, firstSession, alice);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO record.study_session(id, owner_id, title, started_at, timezone)
                VALUES (?, ?, 'writing', now(), 'Asia/Shanghai')
                """, UUID.randomUUID(), alice))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mapsRepresentativeRowsFromEveryBusinessDomain() {
        UUID ownerId = UUID.randomUUID();
        insertUser(ownerId);
        var user = jdbcTemplate.queryForObject(
                "SELECT * FROM record.app_user WHERE id = ?", AccountRowMappers.APP_USER, ownerId);
        assertThat(user.id()).isEqualTo(ownerId);
        assertThat(user.createdAt()).isNotNull();

        UUID calendarEntryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.calendar_entry(id, owner_id, entry_type, event_date, title)
                VALUES (?, ?, 'todo', current_date, 'review')
                """, calendarEntryId, ownerId);
        jdbcTemplate.update("INSERT INTO record.calendar_todo(entry_id, recurrence) VALUES (?, 'daily')",
                calendarEntryId);
        var todo = jdbcTemplate.queryForObject(
                "SELECT * FROM record.calendar_todo WHERE entry_id = ?",
                CalendarRowMappers.CALENDAR_TODO, calendarEntryId);
        assertThat(todo.recurrence()).isEqualTo("daily");
        assertThat(todo.todoTime()).isNull();

        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.finance_account(id, owner_id, name, account_type, currency)
                VALUES (?, ?, 'cash', 'cash', 'CNY')
                """, accountId, ownerId);
        var account = jdbcTemplate.queryForObject(
                "SELECT * FROM record.finance_account WHERE id = ?",
                FinanceRowMappers.FINANCE_ACCOUNT, accountId);
        assertThat(account.annualRatePercent()).isNotNull();
        assertThat(account.balanceMinor()).isZero();

        UUID recordId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.record_entry(id, owner_id, record_date, record_type)
                VALUES (?, ?, current_date, 'journal')
                """, recordId, ownerId);
        var entry = jdbcTemplate.queryForObject(
                "SELECT * FROM record.record_entry WHERE id = ?",
                RecordRowMappers.RECORD_ENTRY, recordId);
        assertThat(entry.ownerId()).isEqualTo(ownerId);
        assertThat(entry.trashedAt()).isNull();

        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.study_session(id, owner_id, title, started_at, timezone)
                VALUES (?, ?, 'reading', now(), 'Asia/Shanghai')
                """, sessionId, ownerId);
        var session = jdbcTemplate.queryForObject(
                "SELECT * FROM record.study_session WHERE id = ?",
                StudyRowMappers.STUDY_SESSION, sessionId);
        assertThat(session.startedAt()).isNotNull();
        assertThat(session.endedAt()).isNull();

        UUID fileId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO record.file_asset
                    (id, owner_id, original_name, media_type, size_bytes, backend_type, storage_key, status)
                VALUES (?, ?, 'note.txt', 'text/plain', 12, 'local', ?, 'available')
                """, fileId, ownerId, UUID.randomUUID().toString());
        var file = jdbcTemplate.queryForObject(
                "SELECT * FROM record.file_asset WHERE id = ?", FileRowMappers.FILE_ASSET, fileId);
        assertThat(file.sizeBytes()).isEqualTo(12);
        assertThat(file.deletedAt()).isNull();
    }

    private void insertUser(UUID id) {
        String unique = id.toString();
        jdbcTemplate.update("""
                INSERT INTO record.app_user
                    (id, email_normalized, password_hash, display_name, display_name_normalized)
                VALUES (?, ?, 'test-hash', ?, ?)
                """, id, unique + "@example.test", unique, unique);
    }
}
