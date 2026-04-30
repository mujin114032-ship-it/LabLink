package com.mujin.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(200, "操作成功"),

    PARAM_ERROR(400, "请求参数错误"),
    UNAUTHORIZED(401, "用户未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    BUSINESS_ERROR(5001, "业务处理失败"),
    FILE_ERROR(5002, "文件处理失败"),
    STORAGE_NOT_ENOUGH(5003, "存储空间不足"),
    CAPTCHA_ERROR(5004, "验证码错误或已失效"),

    SYSTEM_ERROR(500, "系统异常，请稍后重试");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
