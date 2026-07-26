package com.keny.jobassistant.exception;

import com.keny.jobassistant.common.ErrorCode;
import lombok.Getter;

/**
 * 相当于给原本的异常类扩充了两个字段，并且提供了几个构造函数支持传递errorCode
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final String description;

    public BusinessException(String message, int code, String description) {
        super(message);
        this.code = code;
        this.description = description;
    }
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.description = errorCode.getDescription();
    }
    public BusinessException(ErrorCode errorCode,String description) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.description = description;
    }
}
