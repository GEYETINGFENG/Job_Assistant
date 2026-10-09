package com.keny.jobassistant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 给每个请求分配 requestId 并写入 MDC，JSON 日志会自动带上该字段，
 * 一次请求产生的所有日志可以按 requestId 串起来；同时通过响应头返回给客户端便于排查。
 * 请求结束时记录一行访问日志（方法、路径、状态码、耗时），成功请求也能按 requestId 查到。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** 只接受简单格式的上游 ID，防止客户端注入超长或带换行的内容污染日志。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** 独立的 logger 名称，可通过 logging.level.http.access 单独调整或关闭访问日志。 */
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("http.access");

    /** 超过该耗时的请求按 WARN 记录，便于直接筛出慢请求。 */
    private static final long SLOW_REQUEST_MS = 1000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long startNanos = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            logAccess(request, response, failed, startNanos);
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 访问日志：每个请求结束时记录一行，字段以 key=value 输出，JSON 日志中成为独立字段便于检索。
     * 只记录路径，不记录查询参数、请求头和请求体，避免把敏感信息写进日志。
     * 健康检查和指标抓取频率高且没有排查价值，不记录。
     */
    private void logAccess(HttpServletRequest request, HttpServletResponse response, boolean failed, long startNanos) {
        String path = request.getRequestURI();
        if (path.startsWith(request.getContextPath() + "/actuator/")) {
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        // 异常没有被 MVC 处理就抛出过滤器时，响应状态码还没写入，此时按 500 记录。
        int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
        Object[] fields = {kv("method", request.getMethod()), kv("path", path), kv("status", status), kv("durationMs", durationMs)};
        if (status >= 500 || durationMs >= SLOW_REQUEST_MS) {
            ACCESS_LOG.warn("http request {} {} {} {}", fields);
        } else {
            ACCESS_LOG.info("http request {} {} {} {}", fields);
        }
    }
}
