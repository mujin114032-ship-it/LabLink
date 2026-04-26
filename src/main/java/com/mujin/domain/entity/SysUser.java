package com.mujin.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysUser {
    private Long id;
    private String username;
    private String name;

    @JsonIgnore
    private String password;
    @JsonIgnore
    private String phone; // 手机号

    private String role; // ADMIN, MENTOR, STUDENT
    private Integer status; // 1-正常, 0-禁用

    private Long totalStorage; // 总存储空间（字节）
    private Long usedStorage; // 已用存储空间（字节）

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}