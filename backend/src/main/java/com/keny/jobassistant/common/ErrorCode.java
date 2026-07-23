package com.keny.jobassistant.common;

import lombok.Getter;

/**
 * 错误码
 */
@Getter
public enum ErrorCode {

    SUCCESS(0,"ok",""),
    PARAMS_ERROR(40000,"请求参数错误",""),
    NULL_ERROR(40001,"请求数据为空",""),
    NOT_LOGIN(40100,"未登录",""),
    NO_AUTH(40101,"无权限",""),
    SYSTEM_ERROR(50000,"系统内部异常",""),
    RESOURCE_NOT_FOUND(40400, "Resource not found", "The requested resource does not exist");

    private final int code;
    private final String message;
    private final String description;

    ErrorCode(int code, String message, String description) {
        this.code = code;
        this.message = message;
        this.description = description;
    }
    //这里还需要get方法，枚举值是不支持set方法的
}
