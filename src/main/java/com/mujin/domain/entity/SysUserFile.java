package com.mujin.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysUserFile {
    private Long id;                  // 逻辑主键ID
    private Long userId;              // 所属用户ID
    private Long fileId;              // 关联的物理表 sys_file 的 ID
    private String parentId;          // 标记父目录（默认为root）
    private String fileName;          // 用户个人的文件名
    private String isDir;             // 是否为文件夹（0：否，1：是）
    private Integer isDeleted;        // 是否删除（0：否，1：是）
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 更新时间

}