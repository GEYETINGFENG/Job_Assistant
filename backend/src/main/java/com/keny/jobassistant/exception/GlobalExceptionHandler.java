package com.keny.jobassistant.exception;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    /**
     * 处理项目主动抛出的业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<?>> businessExceptionHandler(BusinessException exception) {
        log.warn("BusinessException: code={}, message={}, description={}",
                exception.getCode(), exception.getMessage(), exception.getDescription());

        BaseResponse<?> result = ResultUtils.error(
                exception.getCode(),
                exception.getMessage(),
                exception.getDescription()
        );
        return ResponseEntity.status(exception.getHttpStatus()).body(result);
    }

    /**
     * 处理请求体 JSON 格式错误。
     * 例如Java 字段要求 String，但客户端提交了一个 JSON Object。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<?>> httpMessageNotReadableExceptionHandler(
            HttpMessageNotReadableException exception) {
        log.warn("Invalid request body: {}", exception.getMessage());
        BaseResponse<?> result = ResultUtils.error(
                ErrorCode.PARAMS_ERROR,
                "Request body format is invalid"
        );
        return ResponseEntity.status(ErrorCode.PARAMS_ERROR.getHttpStatus()).body(result);
    }

    /**
     * 处理数据库约束冲突。
     * 例如：唯一字段重复,唯一索引冲突,重复注册同一个账号
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<BaseResponse<?>> dataIntegrityViolationExceptionHandler(
            DataIntegrityViolationException exception) {
        log.warn("Database constraint conflict: {}", exception.getMessage());
        BaseResponse<?> result = ResultUtils.error(
                ErrorCode.ACCOUNT_CONFLICT,
                "The submitted data conflicts with an existing resource"
        );
        return ResponseEntity.status(ErrorCode.ACCOUNT_CONFLICT.getHttpStatus()).body(result);
    }

    /**
     * 处理未预料到的系统异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<?>> exceptionHandler(Exception exception) {
        log.error("Unhandled exception", exception);
        BaseResponse<?> result = ResultUtils.error(ErrorCode.SYSTEM_ERROR);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus()).body(result);
    }
}