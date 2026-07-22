package com.keny.jobassistant.config;

import com.keny.jobassistant.security.CustomAccessDeniedHandler;
import com.keny.jobassistant.security.CustomAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置类。
 *
 * 主要负责：
 * 1. 配置哪些接口可以公开访问
 * 2. 配置哪些接口必须登录
 * 3. 配置哪些接口只有管理员可以访问
 * 4. 配置未登录和无权限时的自定义响应格式
 */

@Configuration
public class SecurityConfig {
    /**
     * 未登录异常处理器：当用户没有登录，却访问需要认证的接口时，会由该处理器返回统一的未登录响应。
     */
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    /**
     * 无权限异常处理器：当用户已经登录，但是没有相应权限时，会由该处理器返回统一的无权限响应。
     */
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    /**
     * 使用构造器注入两个自定义异常处理器。
     */
    public SecurityConfig(
            CustomAuthenticationEntryPoint customAuthenticationEntryPoint,
            CustomAccessDeniedHandler customAccessDeniedHandler
    ) {
        this.customAuthenticationEntryPoint = customAuthenticationEntryPoint;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
    }

    /**
     * 创建 Spring Security 的过滤器链。
     * 所有请求进入 Controller 之前，都会先经过该过滤器链进行认证和权限判断。
     *
     * @param http Spring Security提供的安全配置对象
     * @return 构建完成的安全过滤器链
     * @throws Exception 配置过滤器链过程中可能出现的异常
     */

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                // 关闭 CSRF 防护。Spring Security 默认会对请求进行 CSRF Token 校验。
                .csrf(csrf -> csrf.disable())
                // 关闭 Spring Security 默认的表单登录页面,关闭 HTTP Basic 认证。
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                // 配置 Spring Security 的自定义异常响应
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )
                // Configure API authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Public APIs
                        .requestMatchers(
                                "/user/register",
                                "/user/login",
                                "/user/logout"
                        )
                        .permitAll()
                        // Administrator APIs
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        //其他所有接口必须登录后才能访问
                        .anyRequest()
                        .authenticated()
                );
        return http.build();
    }
}