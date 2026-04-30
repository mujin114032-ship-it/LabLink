package com.mujin.domain.dto;

import lombok.Data;

@Data
public class FileMergeDTO {
    private String identifier;      // 文件的 SHA-256 指纹
    private String filename;        // 原始文件名
    private Integer totalChunks;    // 分片总数
    private String parentId;        // 保存到的父目录 ID
    private String uploadSessionId; // 上传会话 ID，由 /files/verify 返回。merge 时确认当前请求仍然是该文件的上传 owner。
}