package com.keny.jobassistant;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ApplicationEventDTO;
import com.keny.jobassistant.model.dto.ApplicationEventPageDTO;
import com.keny.jobassistant.model.entity.request.ApplicationStatusUpdateRequest;
import com.keny.jobassistant.model.enums.ApplicationStatus;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.ApplicationService;
import com.keny.jobassistant.service.impl.ApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 申请状态机集成测试。
 * 使用 Testcontainers 临时 PostgreSQL 并执行项目全部 Flyway 迁移，验证：
 * 1. 两个请求基于同一个旧状态并发修改时，只有一个成功，最终状态合法；
 * 2. 数据库 CHECK 拒绝非法状态值；
 * 3. 事件历史游标分页在翻页期间插入新事件时不重复、不跳过；
 * 4. 完整生命周期的每一步都记录在事件历史中。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=5"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ApplicationServiceImpl.class)
@Tag("integration")
class ApplicationStatusIntegrationTest {

    private static final long USER_ID = 1L;
    private static final int CONCURRENT_ROUNDS = 20;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jobassistant_application_status_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    private Long applicationId;

    @BeforeEach
    void setUpData() {
        jdbcTemplate.execute("TRUNCATE application_event, application, job, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (id, user_account, user_password) VALUES (?, 'alice', 'hash')", USER_ID);
        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO job (user_id, company_name, job_title)
                VALUES (?, 'Google', 'Software Engineer')
                RETURNING id
                """, Long.class, USER_ID);
        applicationId = jdbcTemplate.queryForObject("""
                INSERT INTO application (user_id, job_id, status)
                VALUES (?, ?, 'SCREENING')
                RETURNING id
                """, Long.class, USER_ID, jobId);
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    /**
     * A 想把 SCREENING 改成 INTERVIEW，B 想把 SCREENING 改成 REJECTED，两个转移单独看都合法。
     * 条件更新 WHERE status = 'SCREENING' 保证只有一个匹配到这一行，另一个得到 409。
     * 重复多轮，降低“碰巧没有并发”的可能。
     */
    @Test
    void concurrentTransitionsFromSameStatusShouldLetOnlyOneWin() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
                jdbcTemplate.update("UPDATE application SET status = 'SCREENING' WHERE id = ?", applicationId);
                jdbcTemplate.update("DELETE FROM application_event");

                CountDownLatch startLatch = new CountDownLatch(1);
                Future<String> toInterview = executor.submit(
                        transitionTask(ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEW, startLatch));
                Future<String> toRejected = executor.submit(
                        transitionTask(ApplicationStatus.SCREENING, ApplicationStatus.REJECTED, startLatch));
                startLatch.countDown();

                List<String> results = List.of(toInterview.get(30, TimeUnit.SECONDS), toRejected.get(30, TimeUnit.SECONDS));
                // 必须刚好一个成功、一个冲突。
                assertThat(results).as("round %d", round).filteredOn("CONFLICT"::equals).hasSize(1);
                String winner = results.stream().filter(result -> !"CONFLICT".equals(result)).findFirst().orElseThrow();
                assertThat(winner).as("round %d", round).isIn("INTERVIEW", "REJECTED");
                String finalStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM application WHERE id = ?", String.class, applicationId);
                assertThat(finalStatus).as("round %d", round).isEqualTo(winner);

                // 失败的一方回滚，事件历史里只有获胜者的一条记录。
                List<String> eventTargets = jdbcTemplate.queryForList(
                        "SELECT to_status FROM application_event WHERE application_id = ?", String.class, applicationId);
                assertThat(eventTargets).as("round %d", round).containsExactly(winner);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseShouldRejectUnknownStatusValue() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE application SET status = 'HIRED' WHERE id = ?", applicationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO application_event (application_id, to_status, operator_user_id)
                VALUES (?, 'HIRED', ?)
                """, applicationId, USER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 第一页取到 e5、e4 以后插入新事件 e6；
     * 游标翻页按 id < 4 继续，后续页面不受新事件影响（offset 分页这里会再次看到 e4）。
     */
    @Test
    void cursorPaginationShouldBeStableWhenNewEventsArrive() {
        List<Long> eventIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            eventIds.add(insertEvent());
        }

        ApplicationEventPageDTO firstPage = applicationService.listEvents(applicationId, null, 2);
        assertThat(firstPage.getItems()).extracting(ApplicationEventDTO::getId)
                .containsExactly(eventIds.get(4), eventIds.get(3));
        assertThat(firstPage.isHasNext()).isTrue();

        insertEvent();

        ApplicationEventPageDTO secondPage = applicationService.listEvents(applicationId, firstPage.getNextCursor(), 2);
        assertThat(secondPage.getItems()).extracting(ApplicationEventDTO::getId)
                .containsExactly(eventIds.get(2), eventIds.get(1));
        assertThat(secondPage.isHasNext()).isTrue();

        ApplicationEventPageDTO lastPage = applicationService.listEvents(applicationId, secondPage.getNextCursor(), 2);
        assertThat(lastPage.getItems()).extracting(ApplicationEventDTO::getId).containsExactly(eventIds.get(0));
        assertThat(lastPage.isHasNext()).isFalse();
        assertThat(lastPage.getNextCursor()).isNull();
    }

    @Test
    void fullLifecycleShouldBeRecordedAndTerminalStatusShouldBeFinal() {
        applicationService.updateStatus(applicationId, request(ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEW));
        applicationService.updateStatus(applicationId, request(ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER));
        applicationService.updateStatus(applicationId, request(ApplicationStatus.OFFER, ApplicationStatus.ACCEPTED));

        // offer 已经接受，拿旧状态硬改回 APPLIED 必须被拒绝。
        assertThatThrownBy(() -> applicationService.updateStatus(applicationId,
                request(ApplicationStatus.OFFER, ApplicationStatus.APPLIED)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION.getCode()));
        // 转移本身合法，但当前状态已经不是 OFFER，必须冲突。
        assertThatThrownBy(() -> applicationService.updateStatus(applicationId,
                request(ApplicationStatus.OFFER, ApplicationStatus.REJECTED)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.APPLICATION_STATUS_CONFLICT.getCode()));

        ApplicationEventPageDTO history = applicationService.listEvents(applicationId, null, 20);
        assertThat(history.getItems()).extracting(ApplicationEventDTO::getFromStatus).containsExactly(
                ApplicationStatus.OFFER, ApplicationStatus.INTERVIEW, ApplicationStatus.SCREENING);
        assertThat(history.getItems()).extracting(ApplicationEventDTO::getToStatus).containsExactly(
                ApplicationStatus.ACCEPTED, ApplicationStatus.OFFER, ApplicationStatus.INTERVIEW);
        assertThat(history.getItems()).extracting(ApplicationEventDTO::getOperatorUserId).containsOnly(USER_ID);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM application WHERE id = ?", String.class, applicationId);
        assertThat(finalStatus).isEqualTo("ACCEPTED");
    }

    private Callable<String> transitionTask(ApplicationStatus from, ApplicationStatus to, CountDownLatch startLatch) {
        return () -> {
            if (!startLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent start");
            }
            try {
                return applicationService.updateStatus(applicationId, request(from, to)).getStatus().name();
            } catch (BusinessException exception) {
                assertThat(exception.getCode()).isEqualTo(ErrorCode.APPLICATION_STATUS_CONFLICT.getCode());
                return "CONFLICT";
            }
        };
    }

    private Long insertEvent() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO application_event (application_id, from_status, to_status, operator_user_id)
                VALUES (?, 'APPLIED', 'SCREENING', ?)
                RETURNING id
                """, Long.class, applicationId, USER_ID);
    }

    private static ApplicationStatusUpdateRequest request(ApplicationStatus from, ApplicationStatus to) {
        ApplicationStatusUpdateRequest request = new ApplicationStatusUpdateRequest();
        request.setFromStatus(from);
        request.setToStatus(to);
        return request;
    }
}
