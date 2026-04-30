package com.mujin.domain.vo;

import lombok.Data;

@Data
public class PublicShareFileVO {
    private String id; // 文件 ID
    private String name;  // 文件名
    private Boolean isDir;  // 是否为目录夹
    private Long size; // 文件大小（字节）
    private String type; // 文件类型
    private String updateTime;  // 更新时间
}
