package com.keny.jobassistant;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.service.JwtTokenService;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户认证和权限控制集成测试。
 1. BCrypt：注册后数据库保存哈希，不是明文
 2. JWT Claim：sub、iss、roles、jti、exp 等正确
 3. 未携带 JWT：返回 401
 4. 携带非法 JWT：返回 401
 5. 普通用户访问管理员接口：返回 403
 6. 管理员访问管理员接口：返回 200
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthenticationIntegrationTest {

    // 模拟 HTTP 请求，不需要真正启动 8080 端口
    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private UserRepository userRepository;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private JwtTokenService jwtTokenService;

    @Resource
    private JwtDecoder jwtDecoder;

    /**
     * 验证用户注册后，数据库保存的是 BCrypt 密码哈希，而不是明文密码。
     */
    @Test
    void userRegisterShouldStoreBcryptPasswordHash() {
        String userAccount = "bcryptTestUser";
        String rawPassword = "TestPassword123";
        // 调用真实的用户注册业务。
        long userId = userService.userRegister(userAccount, rawPassword, rawPassword);
        // 从数据库读取注册后的用户。
        User savedUser = userRepository.findById(userId).orElseThrow();
        // 数据库中的密码不能等于用户提交的明文密码。
        assertThat(savedUser.getUserPassword()).isNotEqualTo(rawPassword);
        // BCrypt 密码哈希通常以 $2 开头。
        assertThat(savedUser.getUserPassword()).startsWith("$2");
        // 正确密码应当可以通过 BCrypt 校验。
        assertThat(passwordEncoder.matches(rawPassword, savedUser.getUserPassword())).isTrue();
        // 错误密码不能通过校验。
        assertThat(passwordEncoder.matches("WrongPassword123", savedUser.getUserPassword())).isFalse();
    }

    /**
     * 验证 JwtTokenService 生成的 Access Token 是否包含正确的 Claims。
     */
    @Test
    void accessTokenShouldContainExpectedJwtClaims() {
        User user = createUser(100L, "jwtTestUser", 0);
        // 使用项目中的真实 JwtTokenService 生成 Token。
        String accessToken = jwtTokenService.generateAccessToken(user);
        // 使用项目中的真实 JwtDecoder 验证签名并解析 Token。
        Jwt jwt = jwtDecoder.decode(accessToken);
        // sub 中应当保存用户 ID。
        assertThat(jwt.getSubject()).isEqualTo("100");
        // iss 应当等于测试配置中的签发者。
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("job-assistant-test");
        // userAccount 应当保存用户账号。
        assertThat(jwt.getClaimAsString("userAccount")).isEqualTo("jwtTestUser");
        // 普通用户应当包含 USER 角色。
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        // 每个 Token 都应当具有唯一的 jti。
        assertThat(jwt.getId()).isNotBlank();
        // Token 应当包含签发时间和过期时间。
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isNotNull();
        // Access Token 有效期应当是配置的 900 秒。
        long expirationSeconds = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds();
        assertThat(expirationSeconds).isEqualTo(900);
    }

    /**
     * 验证没有携带 JWT 时访问管理员接口返回 HTTP 401。
     */
    @Test
    void requestWithoutAccessTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN.getCode()));
    }

    /**
     * 验证携带非法 JWT 时访问管理员接口返回 HTTP 401。
     */
    @Test
    void requestWithInvalidAccessTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-jwt-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN.getCode()));
    }

    /**
     * 验证普通用户访问管理员接口返回 HTTP 403。
     * 这里使用项目真实生成的 JWT
     * 1. roles Claim 中包含 USER
     * 2. SecurityConfig 将 USER 转换为 ROLE_USER
     * 3. /admin/** 要求 ROLE_ADMIN
     * 4. 最终返回 HTTP 403
     */
    @Test
    void normalUserAccessingAdminEndpointShouldReturn403() throws Exception {
        User normalUser = createUser(101L, "normalTestUser", 0);
        String accessToken = jwtTokenService.generateAccessToken(normalUser);

        mockMvc.perform(get("/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH.getCode()));
    }

    /**
     * 验证管理员 JWT 可以访问管理员接口。
     */
    @Test
    void adminAccessingAdminEndpointShouldReturn200() throws Exception {
        User adminUser = createUser(102L, "adminTestUser", 1);
        String accessToken = jwtTokenService.generateAccessToken(adminUser);
        mockMvc.perform(get("/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()));
    }

    /**
     * 创建用于 JWT 测试的用户对象。
     * 这里只需要生成 JWT，不需要将该用户保存到数据库。
     */
    private User createUser(Long id, String userAccount, Integer userRole) {
        User user = new User();
        user.setId(id);
        user.setUserAccount(userAccount);
        user.setUserRole(userRole);
        user.setUserStatus(0);
        user.setIsDelete(User.NOT_DELETED);
        return user;
    }
}