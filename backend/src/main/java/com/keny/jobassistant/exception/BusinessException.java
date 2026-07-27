package com.keny.jobassistant.exception;

import com.keny.jobassistant.common.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 相当于给原本的异常类扩充了两个字段，并且提供了几个构造函数支持传递errorCode
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;//项目内部错误码
    private final HttpStatus httpStatus; //对应的 HTTP 状态码
    private final String description;

    public BusinessException(String message, int code, HttpStatus httpStatus, String description) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.description = description;
    }
    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getMessage(), errorCode.getCode(), errorCode.getHttpStatus(), errorCode.getDescription());
    }
    public BusinessException(ErrorCode errorCode,String description) {
        this(errorCode.getMessage(), errorCode.getCode(), errorCode.getHttpStatus(), description);
    }
}
