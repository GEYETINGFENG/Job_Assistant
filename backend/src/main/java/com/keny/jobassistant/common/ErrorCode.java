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
    // 请求的状态转移不在 ApplicationStatus 转移表中，例如 OFFER -> APPLIED。
    INVALID_STATUS_TRANSITION(40002, HttpStatus.BAD_REQUEST, "Invalid status transition", ""),
    NOT_LOGIN(40100, HttpStatus.UNAUTHORIZED, "Unauthenticated", ""),
    INVALID_CREDENTIALS(40101, HttpStatus.UNAUTHORIZED, "User account or password is incorrect", ""),
    NO_AUTH(40300, HttpStatus.FORBIDDEN, "Forbidden", ""),
    RESOURCE_NOT_FOUND(40400, HttpStatus.NOT_FOUND, "Resource not found", "The requested resource does not exist"),
    ACCOUNT_CONFLICT(40900, HttpStatus.CONFLICT, "User account already exists", ""),
    // 上传任务已经进入终态，或冻结文件时检测到并发修改。
    UPLOAD_CONFLICT(40901, HttpStatus.CONFLICT, "Upload state conflict", ""),
    // 申请状态已经被其他请求修改，客户端基于的 fromStatus 已过期。
    APPLICATION_STATUS_CONFLICT(40902, HttpStatus.CONFLICT, "Application status conflict", ""),
    // 乐观锁冲突。不能和 ACCOUNT_CONFLICT 共用 40900：前端和 worker 都按 code 区分处理方式。
    OPTIMISTIC_LOCK_CONFLICT(40903, HttpStatus.CONFLICT, "Concurrent update conflict", "The resource has been modified by another request"),
    // 其他数据库唯一约束 / 外键冲突。
    DATA_CONFLICT(40904, HttpStatus.CONFLICT, "Data conflict", "The submitted data conflicts with an existing resource"),
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
