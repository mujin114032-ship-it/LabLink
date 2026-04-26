package com.mujin.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一响应结果类
 * @param <T> 响应数据类型
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer code; // 状态码：200代表成功，400代表失败
    private String msg;   // 提示信息
    private T data;       // 数据体
    private LocalDateTime timestamp; // 响应时间戳

    // 成功时的快捷方法（带数据和消息）
    public static <T> Result<T> success(String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg(msg);
        result.setData(data);
        result.setTimestamp(LocalDateTime.now());
        return result;
    }

    // 成功时的快捷方法（仅数据）
    public static <T> Result<T> success(T data) {
        return success("操作成功", data);
    }

    // 成功时的快捷方法（无数据）
    public static <T> Result<T> success() {
        return success("操作成功", null);
    }

    // 失败时的快捷方法（带消息）
    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.setCode(400);
        result.setMsg(msg);
        result.setTimestamp(LocalDateTime.now());
        return result;
    }

    // 失败时的快捷方法（带状态码和消息）
    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setTimestamp(LocalDateTime.now());
        return result;
    }
}