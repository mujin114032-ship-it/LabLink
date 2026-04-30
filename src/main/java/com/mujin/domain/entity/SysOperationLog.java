package com.mujin.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysOperationLog {
    private Long id;                // 主键
    private Long userId;            // 用户 ID
    private String role;            // 角色
    private String module;          // 业务模块
    private String operationType;   // 操作类型
    private String operationDesc;   // 操作描述
    private String requestMethod;   // 请求方法
    private String requestUri;      // 请求 URI
    private String ip;              // 客户端 IP 地址
    private String userAgent;       // 浏览器UA
    private Integer status;        // 操作状态：1成功，0失败
    private String errorMsg;        // 异常信息
    private Long costTime;          // 耗时（毫秒）
    private LocalDateTime createTime; // 创建时间
}
