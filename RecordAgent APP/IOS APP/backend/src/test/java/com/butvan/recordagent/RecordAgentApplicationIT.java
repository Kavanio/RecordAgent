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

    @Autowired private com.butvan.recordagent.service.calendar.TodoService todos;
    @Autowired private com.butvan.recordagent.service.calendar.CalendarProjectionService calendar;
    @Autowired private com.butvan.recordagent.service.finance.FinanceService finance;
    @Autowired private com.butvan.recordagent.service.overview.OverviewService overview;
    @Autowired private com.butvan.recordagent.service.study.StudyService study;

    @Test
    void completeAggregatesExceedOldLimitsAndDetailsCanBePaged() {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        var date = java.time.LocalDate.of(2026, 9, 30);
        var account = finance.createAccount(owner, new com.butvan.recordagent.dto.finance.FinanceDtos.CreateAccount("账户", 0L));
        jdbcTemplate.update("""
                INSERT INTO record.finance_transaction(id,owner_id,account_id,transaction_date,transaction_time,transaction_type,amount_minor,currency)
                SELECT gen_random_uuid(),?,?,'2026-09-30','12:00','expense',1,'CNY' FROM generate_series(1,1001)
                """, owner,account.id());
        jdbcTemplate.update("""
                INSERT INTO record.record_entry(id,owner_id,record_date,record_type,title,content_text)
                SELECT gen_random_uuid(),?,'2026-09-30','journal','标题','不应加载的正文' FROM generate_series(1,1001)
                """,owner);
        jdbcTemplate.update("""
                INSERT INTO record.study_session(id,owner_id,title,started_at,ended_at,timezone)
                SELECT gen_random_uuid(),?,'学习','2026-09-30 12:00:00+08'::timestamptz,
                    '2026-09-30 12:01:00+08'::timestamptz,'Asia/Shanghai' FROM generate_series(1,1001)
                """,owner);
        var projection = calendar.month(owner,java.time.YearMonth.of(2026,9),"Asia/Shanghai");
        assertThat(projection.days()).hasSize(1);
        assertThat(projection.days().getFirst().count()).isEqualTo(3003);
        assertThat(projection.days().getFirst().titles()).hasSize(3);
        var trend = overview.dayTrend(owner,date,"Asia/Shanghai").trend().getLast();
        assertThat(trend.expenseInCents()).isEqualTo(1001);
        assertThat(trend.focusMinutes()).isEqualTo(1001);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> finance.day(owner,date))
                .isInstanceOfSatisfying(com.butvan.recordagent.handler.ApiException.class,error ->
                    assertThat(error.errorCode()).isEqualTo(com.butvan.recordagent.handler.ApiErrorCode.RESULT_LIMIT_EXCEEDED));
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        int offset=0;
        while (true) {
            var page = finance.page(owner,date,offset,100);
            page.items().forEach(row -> assertThat(ids.add(row.id())).isTrue());
            if (!page.hasMore()) break;
            offset=page.nextOffset();
        }
        assertThat(ids).hasSize(1001);
        assertThat(calendar.month(UUID.randomUUID(),java.time.YearMonth.of(2026,9),"Asia/Shanghai").days()).isEmpty();
    }

    @Test
    void todoCompletionAndMonthProgressShareRecurrenceRules() {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        var created = todos.create(owner,new com.butvan.recordagent.dto.calendar.CalendarDtos.CreateTodo(
                "月待办",null,java.time.LocalDate.of(2026,9,15),null,"Asia/Shanghai","monthly"));
        var date=java.time.LocalDate.of(2026,9,30);
        assertThat(todos.day(owner,date,"Asia/Shanghai").todos()).hasSize(1);
        todos.complete(owner,created.id(),date,new com.butvan.recordagent.dto.calendar.CalendarDtos.CompleteOccurrence(true,0L));
        assertThat(todos.day(owner,date,"Asia/Shanghai").todos().getFirst().completed()).isTrue();
        assertThat(todos.progress(owner,java.time.YearMonth.of(2026,9),"Asia/Shanghai"))
                .isEqualTo(new com.butvan.recordagent.service.calendar.TodoService.Progress(1,1));
        assertThat(todos.day(owner,date.minusDays(1),"Asia/Shanghai").todos()).isEmpty();
        assertThat(todos.day(UUID.randomUUID(),date,"Asia/Shanghai").todos()).isEmpty();
    }

    @Test
    void studyAggregatesUseActualDayLengthAcrossDaylightSaving() {
        UUID owner=UUID.randomUUID();
        insertUser(owner);
        jdbcTemplate.update("""
                INSERT INTO record.study_session(id,owner_id,title,started_at,ended_at,timezone)
                VALUES (?,?,'夏令时','2026-03-08 00:00:00-05','2026-03-09 00:00:00-04','America/New_York')
                """,UUID.randomUUID(),owner);
        assertThat(study.minutesOn(owner,java.time.LocalDate.of(2026,3,8),"America/New_York")).isEqualTo(1380);
        assertThat(study.month(owner,java.time.YearMonth.of(2026,3),"America/New_York")).hasSize(1);
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
