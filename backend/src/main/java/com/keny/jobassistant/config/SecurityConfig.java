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
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Spring Security 安全配置类。
 * 主要负责：
 * 1. 配置公开接口
 * 2. 配置需要登录的接口
 * 3. 配置管理员接口
 * 4. 配置 JWT 无状态认证
 * 5. 配置 CORS 跨域访问
 * 6. 配置统一的认证和授权异常响应
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    public SecurityConfig(CustomAuthenticationEntryPoint customAuthenticationEntryPoint,
                          CustomAccessDeniedHandler customAccessDeniedHandler) {
        this.customAuthenticationEntryPoint = customAuthenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
    }

    /**
     * 创建 Spring Security 过滤器链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {

        http
                // 开启 CORS，并使用下面定义的全局 CORS 配置。
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // 当前项目使用 Authorization Bearer Token，不使用 Cookie Session 登录态。
                .csrf(csrf -> csrf.disable())

                // 关闭默认表单登录和 HTTP Basic。
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())

                // JWT 使用无状态认证，不保存 HttpSession。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 配置统一的 401 和 403 响应。
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )

                // 配置接口访问权限。
                .authorizeHttpRequests(auth -> auth
                        // 放行浏览器的 CORS 预检请求。
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 注册和登录接口公开访问。
                        .requestMatchers(HttpMethod.POST, "/user/register", "/user/login").permitAll()
                        // Swagger 和错误接口公开访问。
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error").permitAll()
                        // 管理员接口仅 ADMIN 可以访问。
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // 其他接口必须携带有效 JWT。
                        .anyRequest().authenticated()
                )

                // 开启 JWT Bearer Token 认证
                // 请从Authorization: Bearer Token中读取JWT，验证它是否合法，把JWT中的用户和角色转换成Spring Security的身份信息
                // 如果没有登录返回401，如果权限不足返回403
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                        //使用 JWT 作为认证方式，并指定如何解析 JWT 中的信息
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );

        return http.build();
    }

    /**
     * 配置全局 CORS
     * 只允许指定前端域名访问后端，并允许前端在请求头中携带 JWT。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origin}") String allowedOrigin)
    //从application.yml读取配置文件app.cors.allowed-origin
    {
        //创建 CORS 配置对象,这个对象保存跨域规则
        CorsConfiguration configuration = new CorsConfiguration();
        // 只允许配置文件中指定的前端域名(谁可以访问)
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        // 允许前端使用的 HTTP 方法(可以用什么HTTP方法)
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        // 允许前端发送的请求头(可以发送什么请求头)
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT
        ));

        //不依赖跨域 Cookie，因此不需要携带凭证
        configuration.setAllowCredentials(false);
        // 浏览器可以缓存预检结果 1 小时，减少 OPTIONS 请求次数。
        configuration.setMaxAge(3600L);
        //创建 CORS 配置来源
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        //把这个 CORS 配置应用到所有路径
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 创建 JWT 使用的 HS256 对称密钥。
     */
    @Bean
    public SecretKey jwtSecretKey(@Value("${jwt.secret}") String encodedSecret) {
        byte[] keyBytes;//解码后的真实密钥字节
        try {
            keyBytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT secret must be a valid Base64 value", exception);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret must contain at least 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * 创建 JWT 编码器,生成签名后的JWT
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(jwtSecretKey);
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 创建 JWT 解码器,验证 JWT
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, @Value("${jwt.issuer}") String issuer) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return jwtDecoder;
    }

    /**
     * 将 JWT 中的 roles 转换成 Spring Security 权限
     * ADMIN -> ROLE_ADMIN
     * USER -> ROLE_USER
     */
    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        //创建角色转换器，负责从 JWT 中读取权限信息
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        //指定角色字段名称
        authoritiesConverter.setAuthoritiesClaimName("roles");
        //添加 ROLE_ 前缀
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        //创建给 Spring Security 使用的转换器
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        //设置用户身份来源，sub通常是id
        authenticationConverter.setPrincipalClaimName("sub");
        return authenticationConverter;
    }

    /**
     * 创建 BCrypt 密码加密器。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}