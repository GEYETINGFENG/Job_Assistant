package com.keny.jobassistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 当用户已经登录，但没有足够权限访问某个接口时，拦截请求，并返回项目统一的JSON错误格式
 * 而不是 Spring Security 默认的 403 页面。
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
    //ObjectMapper是Jackson提供的JSON转换工具，可以把Java对象转换成Json
    private final ObjectMapper objectMapper;
    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        // 设置 HTTP 状态码为403
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);

        // 响应编码设置为UTF-8
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 设置返回类型为JSON
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // 创建统一响应对象
        BaseResponse<?> result = ResultUtils.error(ErrorCode.NO_AUTH);

        // 把对象转换成 JSON 并写入响应
        objectMapper.writeValue(response.getWriter(), result);
    }
}