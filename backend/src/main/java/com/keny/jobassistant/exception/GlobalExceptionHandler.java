package com.keny.jobassistant.exception;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException exception, HttpServletResponse response) {
        int code = exception.getCode();
        if (code == ErrorCode.NOT_LOGIN.getCode()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        } else if (code == ErrorCode.NO_AUTH.getCode()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        } else if (code == ErrorCode.RESOURCE_NOT_FOUND.getCode()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } else {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        log.error("BusinessException: {}", exception.getMessage());

        return ResultUtils.error(exception.getCode(), exception.getMessage(), exception.getDescription());
    }

    /**
     * 处理系统异常。
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException exception, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        log.error("RuntimeException", exception);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
    }
}