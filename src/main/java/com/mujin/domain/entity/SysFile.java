package com.mujin.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysFile {
    private Long id;                  // 物理主键ID
    private String fileIdentifier;    // 文件的 SHA-256 指纹
    private Long fileSize;            // 文件大小
    private String filePath;          // MinIO真实存储路径
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 更新时间

}
