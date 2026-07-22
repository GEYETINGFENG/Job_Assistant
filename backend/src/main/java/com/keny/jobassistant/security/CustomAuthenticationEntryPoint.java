package com.keny.jobassistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Handles unauthenticated requests.
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    public CustomAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException{
        // Set the HTTP status code
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Set the response encoding and content type
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // 声明当前接口使用 Bearer Token 认证。
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        // Build the project's unified response
        BaseResponse<?> result = ResultUtils.error(ErrorCode.NOT_LOGIN);
        // Write the response as JSON
        objectMapper.writeValue(response.getWriter(), result);
    }
}