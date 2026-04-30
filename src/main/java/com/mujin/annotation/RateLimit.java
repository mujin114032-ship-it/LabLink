package com.mujin.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流 key，例如 files:chunk、auth:login
     */
    String key() default "";

    /**
     * 限流维度：IP / USER / USER_IP
     */
    RateLimitType type() default RateLimitType.USER;

    /**
     * 时间窗口内最大请求数
     */
    int limit();

    /**
     * 时间窗口，单位秒
     */
    int windowSeconds();

    /**
     * 超限提示
     */
    String message() default "请求过于频繁，请稍后再试";
}
