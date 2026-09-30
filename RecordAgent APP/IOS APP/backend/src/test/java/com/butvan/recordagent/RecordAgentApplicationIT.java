package com.butvan.recordagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.butvan.recordagent.dto.auth.AuthDtos;
import com.butvan.recordagent.dto.calendar.CalendarDtos;
import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.filter.RequestIdFilter;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.mapper.auth.AccountRowMappers;
import com.butvan.recordagent.repository.mapper.calendar.CalendarRowMappers;
import com.butvan.recordagent.repository.mapper.file.FileRowMappers;
import com.butvan.recordagent.repository.mapper.finance.FinanceRowMappers;
import com.butvan.recordagent.repository.mapper.record.RecordRowMappers;
import com.butvan.recordagent.repository.mapper.study.StudyRowMappers;
import com.butvan.recordagent.service.auth.AuthService;
import com.butvan.recordagent.service.auth.AuthenticationRateLimiter;
import com.butvan.recordagent.service.calendar.CalendarProjectionService;
import com.butvan.recordagent.service.calendar.TodoService;
import com.butvan.recordagent.service.finance.FinanceService;
import com.butvan.recordagent.service.mail.MailService;
import com.butvan.recordagent.service.overview.OverviewService;
import com.butvan.recordagent.service.study.StudyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 以隔离的真实 PostgreSQL 校验启动迁移及最小 HTTP 暴露面。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecordAgentApplicationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getProperty("test.postgres.image", "postgres:17.11"))
                    .withDatabaseName("record")
                    .withUsername("recordagent_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private Flyway flyway;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private ObjectMapper objectMapper;

    @LocalServerPort private int port;

    @Test
    void migratesFreshDatabaseAndExposesOnlyHealth() throws Exception {
        Integer migrationCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success = true",
                        Integer.class);
        assertThat(migrationCount).isEqualTo(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'record'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        restTemplate
                                .getForEntity(
                                        "http://localhost:" + port + "/actuator/health",
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> unauthorized =
                restTemplate.getForEntity("http://localhost:" + port + "/api/v1/me", String.class);
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
        jdbcTemplate.update(
                """
                INSERT INTO record.record_entry(id, owner_id, record_date, record_type)
                VALUES (?, ?, current_date, 'journal')
                """,
                recordId,
                alice);
        UUID bobTag = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.record_tag(id, owner_id, name) VALUES (?, ?, 'tag')
                """,
                bobTag,
                bob);
        assertThat(
                        Assertions.catchThrowable(
                                () ->
                                        jdbcTemplate.update(
                                                """
                INSERT INTO record.record_entry_tag(owner_id, record_id, tag_id)
                VALUES (?, ?, ?)
                """,
                                                alice,
                                                recordId,
                                                bobTag)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID bobFile = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.file_asset
                    (id, owner_id, original_name, media_type, size_bytes, backend_type, storage_key, status)
                VALUES (?, ?, 'photo.png', 'image/png', 1, 'local', ?, 'available')
                """,
                bobFile,
                bob,
                UUID.randomUUID().toString());
        assertThat(
                        Assertions.catchThrowable(
                                () ->
                                        jdbcTemplate.update(
                                                """
                INSERT INTO record.record_file(owner_id, record_id, file_id)
                VALUES (?, ?, ?)
                """,
                                                alice,
                                                recordId,
                                                bobFile)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID accountId = UUID.randomUUID();
        assertThat(
                        Assertions.catchThrowable(
                                () ->
                                        jdbcTemplate.update(
                                                """
                INSERT INTO record.finance_account
                    (id, owner_id, name, account_type, currency, balance_minor)
                VALUES (?, ?, 'wallet', 'cash', 'CNY', -1)
                """,
                                                accountId,
                                                alice)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID firstSession = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.study_session(id, owner_id, title, started_at, timezone)
                VALUES (?, ?, 'reading', now(), 'Asia/Shanghai')
                """,
                firstSession,
                alice);
        assertThat(
                        Assertions.catchThrowable(
                                () ->
                                        jdbcTemplate.update(
                                                """
                INSERT INTO record.study_session(id, owner_id, title, started_at, timezone)
                VALUES (?, ?, 'writing', now(), 'Asia/Shanghai')
                """,
                                                UUID.randomUUID(),
                                                alice)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mapsRepresentativeRowsFromEveryBusinessDomain() {
        UUID ownerId = UUID.randomUUID();
        insertUser(ownerId);
        var user =
                jdbcTemplate.queryForObject(
                        "SELECT * FROM record.app_user WHERE id = ?",
                        AccountRowMappers.APP_USER,
                        ownerId);
        assertThat(user.id()).isEqualTo(ownerId);
        assertThat(user.createdAt()).isNotNull();

        UUID calendarEntryId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.calendar_entry(id, owner_id, entry_type, event_date, title)
                VALUES (?, ?, 'todo', current_date, 'review')
                """,
                calendarEntryId,
                ownerId);
        jdbcTemplate.update(
                "INSERT INTO record.calendar_todo(entry_id, recurrence) VALUES (?, 'daily')",
                calendarEntryId);
        var todo =
                jdbcTemplate.queryForObject(
                        "SELECT * FROM record.calendar_todo WHERE entry_id = ?",
                        CalendarRowMappers.CALENDAR_TODO,
                        calendarEntryId);
        assertThat(todo.recurrence()).isEqualTo("daily");
        assertThat(todo.todoTime()).isNull();

        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.finance_account(id, owner_id, name, account_type, currency)
                VALUES (?, ?, 'cash', 'cash', 'CNY')
                """,
                accountId,
                ownerId);
        var account =
                jdbcTemplate.queryForObject(
                        "SELECT * FROM record.finance_account WHERE id = ?",
                        FinanceRowMappers.FINANCE_ACCOUNT,
                        accountId);
        assertThat(account.annualRatePercent()).isNotNull();
        assertThat(account.balanceMinor()).isZero();

        UUID recordId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.record_entry(id, owner_id, record_date, record_type)
                VALUES (?, ?, current_date, 'journal')
                """,
                recordId,
                ownerId);
        var entry =
                jdbcTemplate.queryForObject(
                        "SELECT * FROM record.record_entry WHERE id = ?",
                        RecordRowMappers.RECORD_ENTRY,
                        recordId);
        assertThat(entry.ownerId()).isEqualTo(ownerId);
        assertThat(entry.trashedAt()).isNull();

        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.study_session(id, owner_id, title, started_at, timezone)
                VALUES (?, ?, 'reading', now(), 'Asia/Shanghai')
                """,
                sessionId,
                ownerId);
        var session =
                jdbcTemplate.queryForObject(
                        "SELECT * FROM record.study_session WHERE id = ?",
                        StudyRowMappers.STUDY_SESSION,
                        sessionId);
        assertThat(session.startedAt()).isNotNull();
        assertThat(session.endedAt()).isNull();

        UUID fileId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO record.file_asset
                    (id, owner_id, original_name, media_type, size_bytes, backend_type, storage_key, status)
                VALUES (?, ?, 'note.txt', 'text/plain', 12, 'local', ?, 'available')
                """,
                fileId,
                ownerId,
                UUID.randomUUID().toString());
        var file =
                jdbcTemplate.queryForObject(
                        "SELECT * FROM record.file_asset WHERE id = ?",
                        FileRowMappers.FILE_ASSET,
                        fileId);
        assertThat(file.sizeBytes()).isEqualTo(12);
        assertThat(file.deletedAt()).isNull();
    }

    @Autowired private TodoService todos;
    @Autowired private CalendarProjectionService calendar;
    @Autowired private FinanceService finance;
    @Autowired private OverviewService overview;
    @Autowired private StudyService study;

    @Test
    void completeAggregatesExceedOldLimitsAndDetailsCanBePaged() {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        var date = LocalDate.of(2026, 9, 30);
        var account = finance.createAccount(owner, new FinanceDtos.CreateAccount("账户", 0L));
        jdbcTemplate.update(
                """
                INSERT INTO record.finance_transaction(id,owner_id,account_id,transaction_date,transaction_time,transaction_type,amount_minor,currency)
                SELECT gen_random_uuid(),?,?,'2026-09-30','12:00','expense',1,'CNY' FROM generate_series(1,1001)
                """,
                owner,
                account.id());
        jdbcTemplate.update(
                """
                INSERT INTO record.record_entry(id,owner_id,record_date,record_type,title,content_text)
                SELECT gen_random_uuid(),?,'2026-09-30','journal','标题','不应加载的正文' FROM generate_series(1,1001)
                """,
                owner);
        jdbcTemplate.update(
                """
                INSERT INTO record.study_session(id,owner_id,title,started_at,ended_at,timezone)
                SELECT gen_random_uuid(),?,'学习','2026-09-30 12:00:00+08'::timestamptz,
                    '2026-09-30 12:01:00+08'::timestamptz,'Asia/Shanghai' FROM generate_series(1,1001)
                """,
                owner);
        var projection = calendar.month(owner, YearMonth.of(2026, 9), "Asia/Shanghai");
        assertThat(projection.days()).hasSize(1);
        assertThat(projection.days().getFirst().count()).isEqualTo(3003);
        assertThat(projection.days().getFirst().titles()).hasSize(3);
        var trend = overview.dayTrend(owner, date, "Asia/Shanghai").trend().getLast();
        assertThat(trend.expenseInCents()).isEqualTo(1001);
        assertThat(trend.focusMinutes()).isEqualTo(1001);
        Assertions.assertThatThrownBy(() -> finance.day(owner, date))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(ApiErrorCode.RESULT_LIMIT_EXCEEDED));
        Set<UUID> ids = new HashSet<>();
        int offset = 0;
        while (true) {
            var page = finance.page(owner, date, offset, 100);
            page.items().forEach(row -> assertThat(ids.add(row.id())).isTrue());
            if (!page.hasMore()) break;
            offset = page.nextOffset();
        }
        assertThat(ids).hasSize(1001);
        assertThat(calendar.month(UUID.randomUUID(), YearMonth.of(2026, 9), "Asia/Shanghai").days())
                .isEmpty();
    }

    @Test
    void todoCompletionAndMonthProgressShareRecurrenceRules() {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        var created =
                todos.create(
                        owner,
                        new CalendarDtos.CreateTodo(
                                "月待办",
                                null,
                                LocalDate.of(2026, 9, 15),
                                null,
                                "Asia/Shanghai",
                                "monthly"));
        var date = LocalDate.of(2026, 9, 30);
        assertThat(todos.day(owner, date, "Asia/Shanghai").todos()).hasSize(1);
        todos.complete(owner, created.id(), date, new CalendarDtos.CompleteOccurrence(true, 0L));
        assertThat(todos.day(owner, date, "Asia/Shanghai").todos().getFirst().completed()).isTrue();
        assertThat(todos.progress(owner, YearMonth.of(2026, 9), "Asia/Shanghai"))
                .isEqualTo(new TodoService.Progress(1, 1));
        assertThat(todos.day(owner, date.minusDays(1), "Asia/Shanghai").todos()).isEmpty();
        assertThat(todos.day(UUID.randomUUID(), date, "Asia/Shanghai").todos()).isEmpty();
    }

    @Test
    void studyAggregatesUseActualDayLengthAcrossDaylightSaving() {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        jdbcTemplate.update(
                """
                INSERT INTO record.study_session(id,owner_id,title,started_at,ended_at,timezone)
                VALUES (?,?,'夏令时','2026-03-08 00:00:00-05','2026-03-09 00:00:00-04','America/New_York')
                """,
                UUID.randomUUID(),
                owner);
        assertThat(study.minutesOn(owner, LocalDate.of(2026, 3, 8), "America/New_York"))
                .isEqualTo(1380);
        assertThat(study.month(owner, YearMonth.of(2026, 3), "America/New_York")).hasSize(1);
    }

    @Autowired private AuthenticationRateLimiter limiter;
    @Autowired private AuthService auth;
    @MockitoBean private MailService mail;

    @Test
    void concurrentRetriesWriteOneTransactionAndReplayOriginalBalance() throws Exception {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        var account = finance.createAccount(owner, new FinanceDtos.CreateAccount("并发", 1000L));
        var request =
                new FinanceDtos.CreateTransaction(
                        account.id(),
                        "expense",
                        "分类",
                        "备注",
                        100L,
                        LocalDate.of(2026, 9, 30),
                        LocalTime.NOON,
                        "Asia/Shanghai");
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks =
                    IntStream.range(0, 8)
                            .mapToObj(
                                    i ->
                                            (Callable<FinanceDtos.Transaction>)
                                                    () ->
                                                            finance.createTransaction(
                                                                    owner, request, key))
                            .toList();
            var results = executor.invokeAll(tasks);
            var first = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
            assertThat(first.balanceInCents()).isEqualTo(900);
            assertThat(finance.accounts(owner).getFirst().balanceInCents()).isEqualTo(900);
            assertThat(
                            jdbcTemplate.queryForObject(
                                    "SELECT count(*) FROM record.finance_transaction WHERE owner_id=?",
                                    Integer.class,
                                    owner))
                    .isEqualTo(1);
            finance.createTransaction(owner, request, UUID.randomUUID());
            assertThat(finance.createTransaction(owner, request, key).balanceInCents())
                    .isEqualTo(900);
        }
        var different =
                new FinanceDtos.CreateTransaction(
                        account.id(),
                        "expense",
                        "分类",
                        "备注",
                        101L,
                        request.date(),
                        request.time(),
                        request.timeZone());
        Assertions.assertThatThrownBy(() -> finance.createTransaction(owner, different, key))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
        UUID other = UUID.randomUUID();
        insertUser(other);
        Assertions.assertThatThrownBy(() -> finance.createTransaction(other, request, key))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    @Test
    void failedFinancialWriteRollsBackAndAllowsSameKeyRetry() {
        UUID owner = UUID.randomUUID();
        insertUser(owner);
        var account = finance.createAccount(owner, new FinanceDtos.CreateAccount("重试", 0L));
        var expense =
                new FinanceDtos.CreateTransaction(
                        account.id(),
                        "expense",
                        null,
                        null,
                        100L,
                        LocalDate.of(2026, 9, 30),
                        LocalTime.NOON,
                        "Asia/Shanghai");
        UUID key = UUID.randomUUID();
        Assertions.assertThatThrownBy(() -> finance.createTransaction(owner, expense, key))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM record.finance_write_request WHERE owner_id=?",
                                Integer.class,
                                owner))
                .isZero();
        var income =
                new FinanceDtos.CreateTransaction(
                        account.id(),
                        "income",
                        null,
                        null,
                        100L,
                        expense.date(),
                        expense.time(),
                        expense.timeZone());
        finance.createTransaction(owner, income, UUID.randomUUID());
        assertThat(finance.createTransaction(owner, expense, key).balanceInCents()).isZero();
    }

    @Test
    void rateLimitIsSharedAtomicAndExpiredWindowResets() throws Exception {
        String scope = "test_quota";
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks =
                    IntStream.range(0, 12)
                            .mapToObj(
                                    i ->
                                            (Callable<Boolean>)
                                                    () -> limiter.consume(scope, key, 60, 3))
                            .toList();
            int allowed = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) allowed++;
            assertThat(allowed).isEqualTo(3);
        }
        jdbcTemplate.update(
                "UPDATE record.auth_rate_bucket SET window_start=now()-interval '2 minutes' WHERE scope=?",
                scope);
        assertThat(limiter.consume(scope, key, 60, 3)).isTrue();
    }

    @Test
    void authenticationRevokesSessionsAndFailedCodeAttemptsRemainCommitted() {
        String email = UUID.randomUUID() + "@example.test";
        AtomicReference<String> code = new AtomicReference<>();
        Mockito.doAnswer(
                        call -> {
                            code.set(call.getArgument(1));
                            return null;
                        })
                .when(mail)
                .send(ArgumentMatchers.eq(email), ArgumentMatchers.anyString());
        auth.sendCode(new AuthDtos.CodeRequest(email, "register"));
        var session =
                auth.register(new AuthDtos.RegisterRequest(email, code.get(), "valid-password"));
        assertThat(auth.accessSession(session.token())).isPresent();
        var refreshed = auth.refresh(session.refreshToken());
        assertThat(auth.accessSession(session.token())).isEmpty();
        auth.logout(auth.accessSession(refreshed.token()).orElseThrow().id());
        assertThat(auth.accessSession(refreshed.token())).isEmpty();
        Assertions.assertThatThrownBy(() -> auth.refresh(refreshed.refreshToken()))
                .isInstanceOf(ApiException.class);
        var active = auth.passwordLogin(new AuthDtos.PasswordLoginRequest(email, "valid-password"));
        jdbcTemplate.update(
                "UPDATE record.app_user SET status='disabled' WHERE id=?", active.user().id());
        assertThat(auth.accessSession(active.token())).isEmpty();
        jdbcTemplate.update(
                "UPDATE record.app_user SET status='active' WHERE id=?", active.user().id());
        auth.sendCode(new AuthDtos.CodeRequest(email, "reset_password"));
        auth.resetPassword(
                new AuthDtos.ResetPasswordRequest(email, code.get(), "replacement-password"));
        assertThat(auth.accessSession(active.token())).isEmpty();
        auth.sendCode(new AuthDtos.CodeRequest(email, "login"));
        String valid = code.get();
        for (int i = 0; i < 5; i++)
            Assertions.assertThatThrownBy(
                            () -> auth.codeLogin(new AuthDtos.CodeLoginRequest(email, "invalid")))
                    .isInstanceOf(ApiException.class);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT attempt_count FROM record.email_challenge WHERE email_normalized=? AND purpose='login'",
                                Integer.class,
                                email))
                .isEqualTo(5);
        Assertions.assertThatThrownBy(
                        () -> auth.codeLogin(new AuthDtos.CodeLoginRequest(email, valid)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void passwordLoginLimitReturnsSafeHttp429() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        var body = new AuthDtos.PasswordLoginRequest(email, "wrong-password");
        for (int index = 0; index < 11; index++) {
            var result =
                    restTemplate.postForEntity(
                            "http://localhost:" + port + "/api/v1/auth/password-login",
                            body,
                            String.class);
            assertThat(result.getStatusCode())
                    .isEqualTo(index < 10 ? HttpStatus.UNAUTHORIZED : HttpStatus.TOO_MANY_REQUESTS);
            var error = objectMapper.readTree(result.getBody());
            assertThat(error.path("requestId").asText())
                    .isEqualTo(result.getHeaders().getFirst(RequestIdFilter.HEADER_NAME));
            assertThat(result.getBody()).doesNotContain(email).doesNotContain("wrong-password");
        }
    }

    @Test
    void upgradesExistingV4DatabaseWithoutLosingBusinessRows() throws Exception {
        String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE DATABASE " + database);
        String url =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getMappedPort(5432)
                        + "/"
                        + database;
        var source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var legacy = Flyway.configure().dataSource(source).target("4").load();
        assertThat(legacy.migrate().migrationsExecuted).isEqualTo(4);
        var oldJdbc = new JdbcTemplate(source);
        UUID owner = UUID.randomUUID();
        oldJdbc.update(
                "INSERT INTO record.app_user(id,email_normalized,password_hash,display_name,display_name_normalized) VALUES(?,'upgrade@example.test','test-hash','升级用户','升级用户')",
                owner);
        oldJdbc.update(
                "INSERT INTO record.finance_account(id,owner_id,name,account_type,currency,balance_minor) VALUES(?,?,'旧账户','cash','CNY',123)",
                UUID.randomUUID(),
                owner);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted)
                .isEqualTo(1);
        assertThat(
                        oldJdbc.queryForObject(
                                "SELECT balance_minor FROM record.finance_account WHERE owner_id=?",
                                Long.class,
                                owner))
                .isEqualTo(123);
        assertThat(
                        oldJdbc.queryForObject(
                                "SELECT count(*) FROM record.finance_write_request", Integer.class))
                .isZero();
    }

    private void insertUser(UUID id) {
        String unique = id.toString();
        jdbcTemplate.update(
                """
                INSERT INTO record.app_user
                    (id, email_normalized, password_hash, display_name, display_name_normalized)
                VALUES (?, ?, 'test-hash', ?, ?)
                """,
                id,
                unique + "@example.test",
                unique,
                unique);
    }
}
