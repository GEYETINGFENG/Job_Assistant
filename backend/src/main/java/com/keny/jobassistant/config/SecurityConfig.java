package com.keny.jobassistant.config;
import com.keny.jobassistant.security.CustomAccessDeniedHandler;
import com.keny.jobassistant.security.CustomAuthenticationEntryPoint;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Spring Security 安全配置类。
 * 主要负责：
 * 1. 配置公开接口
 * 2. 配置需要登录的接口
 * 3. 配置仅管理员可以访问的接口
 * 4. 配置 JWT 无状态认证
 * 5. 配置未登录和无权限时的统一异常响应
 * 6. 配置 JWT 的生成器和解析器
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 未登录异常处理器。当用户没有携带 JWT，或者携带的 JWT 无效、已过期时，
     * 会由该处理器返回统一的未登录响应。
     */
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

    /**
     * 无权限异常处理器：当用户已经通过 JWT 完成认证，但没有访问某个接口所需的角色或权限时，
     * 会由该处理器返回统一的无权限响应。
     */
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    //使用构造器注入自定义的 401 和 403 异常处理器。
    public SecurityConfig(CustomAuthenticationEntryPoint customAuthenticationEntryPoint,
                          CustomAccessDeniedHandler customAccessDeniedHandler) {
        this.customAuthenticationEntryPoint = customAuthenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
    }

    /**
     * 创建 Spring Security 过滤器链。
     * 所有 HTTP 请求在进入 Controller 之前，都会先经过该过滤器链进行 JWT 认证和权限校验。
     * @param http Spring Security 提供的安全配置对象
     * @param jwtAuthenticationConverter JWT 权限转换器
     * @return 构建完成的安全过滤器链
     * @throws Exception 配置过程中可能出现的异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {
        http
                //关闭 CSRF 防护,关闭 Spring Security 默认的表单登录页面,关闭 HTTP Basic 认证。
                .csrf(csrf -> csrf.disable())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                //设置为无状态认证 Authorization: Bearer <token>
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                //配置认证和授权失败时的统一响应格式。
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )
                //配置接口访问规则,Spring Security 会按照从上到下的顺序匹配规则。
                .authorizeHttpRequests(auth -> auth
                        //允许浏览器发送跨域预检请求。
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        //注册和登录接口可以公开访问。
                        .requestMatchers(HttpMethod.POST, "/user/register", "/user/login")
                        .permitAll()
                        //Swagger 接口文档公开访问。
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/error"
                        )
                        .permitAll()
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        //其他所有接口必须完成 JWT 认证
                        .anyRequest()
                        .authenticated()
                )
                /*
                 * 开启 OAuth2 Resource Server 的 JWT 认证功能。
                 * Spring Security 会自动读取请求头：Authorization: Bearer <JWT>
                 */
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );
        return http.build();
    }

    /**
     * 创建 JWT 使用的 HS256 对称密钥。
     * application-secret.yml 中保存的是 Base64 编码后的字符串：
     * 这里会将 Base64 字符串解码为字节数组，
     * 再创建真正用于 JWT 签名和验证的 SecretKey。
     * @param encodedSecret Base64 编码后的 JWT 密钥
     * @return HS256 对称密钥
     */
    @Bean
    public SecretKey jwtSecretKey(@Value("${jwt.secret}") String encodedSecret) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT 密钥必须是合法的 Base64 字符串", exception);
        }

        //HS256使用256位(32字节)密钥,application-secret.yml 中保存的是 Base64 编码后的字符串
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT 密钥长度不能少于 32 字节");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * 创建 JWT 编码器。
     * JwtEncoder 负责在用户登录成功后生成并签名 JWT。
     * 该 Bean 后续会在 JwtTokenService 中使用。
     * @param jwtSecretKey JWT 对称密钥
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(jwtSecretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 创建 JWT 解码器。
     * JwtDecoder 负责：
     * 1. 验证 JWT 签名
     * 2. 验证 JWT 是否过期
     * 3. 验证 JWT 签发者
     * @param jwtSecretKey JWT 对称密钥
     * @param issuer JWT 签发者
     * @return JWT 解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, @Value("${jwt.issuer}") String issuer) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        //默认检查 exp、nbf 等字段，同时检查 iss 是否与配置中的issuer一致
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return jwtDecoder;
    }

    /**
     * 创建 JWT 认证转换器。
     * 负责将 JWT 中的 roles 字段转换为Spring Security 可以识别的权限。
     * @return JWT 认证转换器
     */
    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        //指定从 JWT 的 roles 字段中读取角色
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        /*
         * 将 JWT 的 sub 字段作为当前登录用户标识。
         * 后续可以通过 authentication.getName()
         * 获取 JWT 中的 sub。建议生成 JWT 时将用户 ID 放入 sub。
         */
        authenticationConverter.setPrincipalClaimName("sub");
        return authenticationConverter;
    }

    /**
     * 创建密码加密器。
     * 注册时：
     * passwordEncoder.encode(原始密码)
     * 登录时：
     * passwordEncoder.matches(原始密码, 数据库中的加密密码)
     * @return BCrypt 密码加密器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}