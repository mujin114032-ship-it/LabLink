package com.mujin.exception;

import com.mujin.domain.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务运行时异常
     * 目前你的 Service 层主要抛 RuntimeException，所以这个最重要
     */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /**
     * 缺少请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数：{}", e.getParameterName());
        return Result.error(400, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * JSON 请求体格式错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误：{}", e.getMessage());
        return Result.error(400, "请求体格式错误，请检查 JSON 格式");
    }

    /**
     * 请求方法错误，例如 POST 接口用了 GET
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{}", e.getMethod());
        return Result.error(405, "不支持当前请求方法：" + e.getMethod());
    }

    /**
     * 上传文件过大
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制：{}", e.getMessage());
        return Result.error(400, "上传文件超过大小限制");
    }

    /**
     * 系统兜底异常
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常：", e);
        return Result.error(500, "系统异常，请稍后重试");
    }
}