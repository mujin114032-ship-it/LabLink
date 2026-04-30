package com.mujin.aspect;

import com.mujin.annotation.RateLimit;
import com.mujin.annotation.RateLimitType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

import java.util.Collections;

/**
 * 接口限流切面
 * 基于 Redis + Lua 脚本实现分布式限流
 * 对标注 @RateLimit 注解的接口进行限流控制
 *
 * @author mujin
 */
@Aspect
@Component
@Order(1) // 优先级最高，比日志切面先执行
@RequiredArgsConstructor
public class RateLimitAspect {

    /**
     * Redis 操作模板
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * Redis 限流 Lua 脚本（保证原子性）
     * 作用：自增计数 + 首次设置过期时间
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    // 静态代码块初始化 Lua 脚本
    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        // 返回值类型：Long（自增后的计数）
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
        // Lua 脚本逻辑：
        // 1. 对 key 执行 incr 自增
        // 2. 如果是第一次访问（值=1），设置过期时间
        // 3. 返回当前计数
        RATE_LIMIT_SCRIPT.setScriptText("""
            local current = redis.call('incr', KEYS[1])
            if tonumber(current) == 1 then
                redis.call('expire', KEYS[1], ARGV[1])
            end
            return current
        """);
    }

    /**
     * 环绕通知：拦截带有 @RateLimit 注解的方法
     *
     * @param joinPoint  切点
     * @param rateLimit  注解对象，获取限流配置
     * @return 接口正常返回值
     * @throws Throwable 超过限流则抛出异常
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 获取当前请求上下文
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        // 非 Web 请求（如定时任务、内部方法），直接放行
        if (attributes == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();

        // 构建限流 key：未指定则使用请求 URI
        String apiKey = rateLimit.key().isBlank()
                ? request.getRequestURI()
                : rateLimit.key();

        // 根据限流类型（IP/用户/IP+用户）构建唯一标识
        String identity = buildIdentity(request, rateLimit.type());

        // 最终 Redis Key = 前缀 + 接口标识 + 身份标识
        String redisKey = "rate_limit:" + apiKey + ":" + identity;

        // 执行 Lua 脚本，获取当前窗口内的访问次数
        Long count = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(redisKey),
                String.valueOf(rateLimit.windowSeconds())
        );

        // 如果访问次数超过限制，直接抛出异常
        if (count != null && count > rateLimit.limit()) {
            throw new RuntimeException(rateLimit.message());
        }

        // 限流通过，执行目标接口
        return joinPoint.proceed();
    }

    /**
     * 根据限流类型构建用户唯一标识
     *
     * @param request 请求对象
     * @param type    限流类型：IP / 用户 / IP+用户
     * @return 唯一标识字符串
     */
    private String buildIdentity(HttpServletRequest request, RateLimitType type) {
        // 获取客户端真实IP
        String ip = getClientIp(request);

        // 获取登录用户ID，未登录则标记为匿名用户
        Object userIdObj = request.getAttribute("userId");
        String userId = userIdObj == null ? "anonymous" : String.valueOf(userIdObj);

        // 仅根据 IP 限流
        if (type == RateLimitType.IP) {
            return "ip:" + ip;
        }

        // 根据 用户ID + IP 限流（最严格）
        if (type == RateLimitType.USER_IP) {
            return "user:" + userId + ":ip:" + ip;
        }

        // 默认：根据用户ID限流
        return "user:" + userId;
    }

    /**
     * 获取客户端真实IP（兼容Nginx/代理转发）
     * 优先从请求头获取，兜底使用远程地址
     */
    private String getClientIp(HttpServletRequest request) {
        // 获取经过多层代理后的IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }

        // 获取Nginx直接转发的真实IP
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }

        // 兜底：直接获取请求IP
        return request.getRemoteAddr();
    }
}
