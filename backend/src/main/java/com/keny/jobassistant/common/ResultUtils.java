package com.keny.jobassistant.common;

/**
 * 返回工具类
 */
public class ResultUtils {
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(
                ErrorCode.SUCCESS.getCode(),
                data,
                ErrorCode.SUCCESS.getMessage()
        );
    }

    public static BaseResponse<?> error(ErrorCode errorCode) {
        //return new BaseResponse<>(errorCode.getCode(),null,errorCode.getMessage(),errorCode.getDescription());
        return new BaseResponse<>(errorCode);
        //两种return都行，只是下面这种是在BaseResponse中定义了可以接收errorCode更方便
    }
    public static BaseResponse<?> error(ErrorCode errorCode, String message,String description) {
        return new BaseResponse<>(errorCode.getCode(),null,message,description);
    }
    public static BaseResponse<?> error(int code, String message,String description) {
        return  new BaseResponse<>(code ,null,message,description);
    }
    public static BaseResponse<?> error(ErrorCode errorCode,String description) {
        return  new BaseResponse<>(errorCode.getCode(),null,errorCode.getMessage(),description);
    }
    private ResultUtils() {
        // Prevent utility class instantiation
    }
}
