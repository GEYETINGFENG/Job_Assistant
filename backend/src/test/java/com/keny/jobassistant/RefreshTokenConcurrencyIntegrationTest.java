package com.keny.jobassistant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.model.vo.UserLoginVO;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Refresh Token 并发刷新集成测试。
 * 模拟同一个用户打开两个浏览器标签页，
 * 两个标签页同时使用同一个 Refresh Token 发起刷新请求。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RefreshTokenConcurrencyIntegrationTest {

    //模拟发送 HTTP 请求
    @Resource
    private MockMvc mockMvc;

    //JSON 序列化和解析工具
    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private UserService userService;

    //用于直接检查测试数据库中的 Refresh Token 状态
    @Resource
    private JdbcTemplate jdbcTemplate;

    // 当前测试创建的用户 ID,测试结束后根据该 ID 清理数据
    private Long createdUserId;

    // 验证两个标签页同时刷新时，不会误杀 Token Family
    @Test
    void concurrentRefreshRequestsShouldNotRevokeTokenFamily() throws Exception {
        String userAccount = "concurrent" + UUID.randomUUID().toString().replace("-", "");
        String password = "TestPassword123";

        //注册并登录测试用户
        createdUserId = userService.userRegister(userAccount, password, password);

        UserLoginVO loginResult = userService.userLogin(userAccount, password);
        String originalRefreshToken = loginResult.getRefreshToken();
        assertThat(originalRefreshToken).isNotBlank();

        // 创建两个工作线程，分别模拟两个浏览器标签页
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        /*
         * readyLatch：等待两个线程都准备完成。
         * startLatch：同时释放两个线程，让它们尽可能同时发起刷新请求。
         */
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Callable<MvcResult> refreshTask = () -> {
            // 当前标签页已经准备好。
            readyLatch.countDown();
            // 等待两个标签页一起开始。
            boolean started = startLatch.await(5, TimeUnit.SECONDS);
            if (!started) {
                throw new IllegalStateException("并发刷新测试未能按时开始");
            }
            return performRefreshRequest(originalRefreshToken);
        };

        try {
            // 提交两个模拟标签页的刷新任务。
            Future<MvcResult> firstFuture = executorService.submit(refreshTask);
            Future<MvcResult> secondFuture = executorService.submit(refreshTask);

            // 确认两个线程都已经进入准备状态。
            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // 同时释放两个线程。
            startLatch.countDown();

            // 等待两个刷新请求完成。
            MvcResult firstResult = firstFuture.get(10, TimeUnit.SECONDS);
            MvcResult secondResult = secondFuture.get(10, TimeUnit.SECONDS);

            /*
             * 两个标签页都必须刷新成功。
             * 原来的严格复用检测通常会出现：一个请求返回 200，另一个请求返回 401。
             * 增加短暂幂等窗口后，两个请求都应该返回 200。
             */
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);

            // 读取两个响应中的 data。
            JsonNode firstData = readResponseData(firstResult);
            JsonNode secondData = readResponseData(secondResult);

            String firstAccessToken = firstData.get("accessToken").asText();
            String secondAccessToken = secondData.get("accessToken").asText();

            String firstRefreshToken = firstData.get("refreshToken").asText();
            String secondRefreshToken = secondData.get("refreshToken").asText();

            /*
             * 两个标签页必须获得完全相同的 Token 组合。
             * 这证明第二个请求命中了旧 Token A 对应的幂等缓存,而不是再次生成另一套 Token。
             */
            assertThat(firstAccessToken).isNotBlank();
            assertThat(firstRefreshToken).isNotBlank();
            assertThat(secondAccessToken).isEqualTo(firstAccessToken);
            assertThat(secondRefreshToken).isEqualTo(firstRefreshToken);

            /*
             * 查询数据库中该用户的全部 Refresh Token。
             * 此时应该只有两条记录：
             * 1. Token A：登录时生成，已经撤销
             * 2. Token B：并发刷新后生成，仍然有效
             */
            List<Map<String, Object>> tokenRows = jdbcTemplate.queryForList(
                    """
                    SELECT id, family_id, revoked_at
                    FROM refresh_tokens
                    WHERE user_id = ?
                    ORDER BY id
                    """,
                    createdUserId
            );

            // 两个并发请求只能真正执行一次轮换，所以只能有两条记录。
            assertThat(tokenRows).hasSize(2);
            Map<String, Object> oldTokenRow = tokenRows.get(0);
            Map<String, Object> newTokenRow = tokenRows.get(1);
            // 旧Token A 已经被正常轮换，因此 revoked_at 必须有值
            assertThat(oldTokenRow.get("revoked_at")).isNotNull();
            //新Token B 必须仍然有效。如果第二个标签页触发了整族撤销，这里的 revoked_at 就会有值
            assertThat(newTokenRow.get("revoked_at")).isNull();
            //新旧 Token 必须属于同一个 Token Family
            assertThat(newTokenRow.get("family_id")).isEqualTo(oldTokenRow.get("family_id"));
            //当前用户只能有一个有效 Refresh Token
            long activeTokenCount = tokenRows.stream()
                    .filter(row -> row.get("revoked_at") == null)
                    .count();
            assertThat(activeTokenCount).isEqualTo(1);

            /*
             * 再使用两个标签页得到的 Refresh Token B 刷新一次。
             * 如果第二个并发请求误杀了 Token Family，那么这个请求会返回 HTTP 401。
             * 返回 HTTP 200，证明 Token B 仍然有效，
             * Token Family 没有被误杀。
             */
            MvcResult nextRefreshResult = performRefreshRequest(firstRefreshToken);
            assertThat(nextRefreshResult.getResponse().getStatus()).isEqualTo(200);
            JsonNode nextData = readResponseData(nextRefreshResult);
            assertThat(nextData.get("accessToken").asText()).isNotBlank();
            assertThat(nextData.get("refreshToken").asText()).isNotBlank();
        } finally {
            executorService.shutdownNow();
        }
    }

    //调用 Refresh Token 刷新接口
    private MvcResult performRefreshRequest(String refreshToken) throws Exception {
        byte[] requestBody = objectMapper.writeValueAsBytes(
                Map.of("refreshToken", refreshToken)
        );
        return mockMvc.perform(
                        post("/user/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andReturn();
    }
    // 从统一响应结构中读取 data
    private JsonNode readResponseData(MvcResult result) throws Exception {
        String responseBody = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(responseBody);
        // 业务状态码必须为成功
        assertThat(root.get("code").asInt()).isEqualTo(0);
        // data 不能为空
        assertThat(root.get("data")).isNotNull();
        assertThat(root.get("data").isNull()).isFalse();
        return root.get("data");
    }

    /**
     * 测试结束后清理本次测试创建的数据。
     * 必须先删除 Refresh Token，
     * 再删除用户，避免违反外键约束。
     */
    @AfterEach
    void cleanUp() {
        if (createdUserId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM refresh_tokens WHERE user_id = ?",
                createdUserId
        );
        jdbcTemplate.update(
                "DELETE FROM users WHERE id = ?",
                createdUserId
        );
        createdUserId = null;
    }
}