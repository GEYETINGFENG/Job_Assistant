package com.keny.jobassistant.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * code 是项目内部业务错误码，
 * httpStatus 是对应的 HTTP状态码
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, HttpStatus.OK, "ok", ""),
    PARAMS_ERROR(40000, HttpStatus.BAD_REQUEST, "Invalid request parameters", ""),
    NULL_ERROR(40001, HttpStatus.BAD_REQUEST, "Request data cannot be null", ""),
    NOT_LOGIN(40100, HttpStatus.UNAUTHORIZED, "Unauthenticated", ""),
    INVALID_CREDENTIALS(40101, HttpStatus.UNAUTHORIZED, "User account or password is incorrect", ""),
    NO_AUTH(40300, HttpStatus.FORBIDDEN, "Forbidden", ""),
    RESOURCE_NOT_FOUND(40400, HttpStatus.NOT_FOUND, "Resource not found", "The requested resource does not exist"),
    ACCOUNT_CONFLICT(40900, HttpStatus.CONFLICT, "User account already exists", ""),
    SYSTEM_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;
    private final String description;

    ErrorCode(int code, HttpStatus httpStatus, String message, String description) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
        this.description = description;
    }
    //这里还需要get方法，枚举值是不支持set方法的
}
