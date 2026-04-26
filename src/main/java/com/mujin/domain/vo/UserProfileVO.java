package com.mujin.domain.vo;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class UserProfileVO {
    private String username;
    private Long storageUsed;  // 字节数
    private Long storageTotal; // 字节数
}