package com.mujin.aspect;

import com.mujin.annotation.OperationLog;
import com.mujin.domain.entity.SysOperationLog;
import com.mujin.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 操作日志切面类
 * 基于AOP实现全局操作日志自动记录功能
 * 对标注了 @OperationLog 注解的方法进行环绕增强，记录操作人、模块、耗时、状态等信息
 *
 * @author mujin
 */
@Slf4j
@Aspect
@Component
@Order(2) // 切面执行顺序，数字越小优先级越高
@RequiredArgsConstructor // 构造器注入依赖
public class OperationLogAspect {

    /**
     * 操作日志持久层Mapper，用于将日志写入数据库
     */
    private final OperationLogMapper operationLogMapper;

    /**
     * 环绕通知：拦截带有 @OperationLog 注解的方法
     * 记录方法执行耗时、执行状态、异常信息，并统一入库
     *
     * @param joinPoint 切点对象，可获取目标方法信息
     * @return 目标方法执行结果
     * @throws Throwable 目标方法抛出的异常
     */
    @Around("@annotation(com.mujin.annotation.OperationLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 记录方法开始执行时间
        long start = System.currentTimeMillis();

        // 创建操作日志实体
        SysOperationLog opLog = new SysOperationLog();
        Throwable throwable = null;

        try {
            // 填充日志基础信息：模块、类型、描述、用户信息、请求信息等
            fillBasicInfo(joinPoint, opLog);

            // 执行目标方法
            Object result = joinPoint.proceed();

            // 方法执行成功，设置状态为1
            opLog.setStatus(1);
            return result;

        } catch (Throwable e) {
            // 捕获方法执行异常
            throwable = e;

            // 执行失败，状态设为0，并记录异常信息（限制长度）
            opLog.setStatus(0);
            opLog.setErrorMsg(limit(e.getMessage(), 1000));

            // 抛出原异常，不影响业务流程
            throw e;

        } finally {
            // 无论成功失败，都记录执行耗时和创建时间
            opLog.setCostTime(System.currentTimeMillis() - start);
            opLog.setCreateTime(LocalDateTime.now());

            try {
                // 插入操作日志到数据库
                operationLogMapper.insert(opLog);
            } catch (Exception logException) {
                // 日志写入失败只打印警告，不影响主业务执行
                log.warn("操作日志写入失败，不影响主业务", logException);
            }
        }
    }

    /**
     * 填充操作日志的基础信息
     * 包括注解信息、用户信息、请求信息（IP、请求方式、URI等）
     *
     * @param joinPoint 切点
     * @param opLog     待填充的日志对象
     */
    private void fillBasicInfo(ProceedingJoinPoint joinPoint, SysOperationLog opLog) {
        // 获取目标方法签名和方法对象
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 获取方法上的 OperationLog 注解
        OperationLog annotation = method.getAnnotation(OperationLog.class);

        // 从注解中获取业务模块、操作类型、操作描述
        opLog.setModule(annotation.module());
        opLog.setOperationType(annotation.type());
        opLog.setOperationDesc(annotation.desc());

        // 获取当前请求上下文
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        // 非Web请求环境直接返回
        if (attributes == null) {
            return;
        }

        HttpServletRequest request = attributes.getRequest();

        // 从请求域中获取登录用户ID和角色（由登录拦截器存入）
        Object userId = request.getAttribute("userId");
        Object role = request.getAttribute("role");

        if (userId instanceof Long) {
            opLog.setUserId((Long) userId);
        }

        if (role != null) {
            opLog.setRole(String.valueOf(role));
        }

        // 填充请求相关信息
        opLog.setRequestMethod(request.getMethod());
        opLog.setRequestUri(request.getRequestURI());
        opLog.setIp(getClientIp(request));
        // 限制浏览器UA长度，避免超长
        opLog.setUserAgent(limit(request.getHeader("User-Agent"), 512));
    }

    /**
     * 获取客户端真实IP
     * 兼容代理、Nginx转发等场景，依次从请求头获取真实IP
     *
     * @param request HttpServletRequest对象
     * @return 客户端真实IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        // 获取经过代理服务器后的真实IP（多个IP取第一个）
        String ip = request.getHeader("X-Forwarded-For");

        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }

        // 获取Nginx直接转发的真实IP
        ip = request.getHeader("X-Real-IP");

        if (ip != null && !ip.isBlank()) {
            return ip;
        }

        // 兜底：直接获取远程地址
        return request.getRemoteAddr();
    }

    /**
     * 字符串长度限制工具方法
     * 防止数据库字段超长导致插入失败
     *
     * @param value     原字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
