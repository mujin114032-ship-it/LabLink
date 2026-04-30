package com.mujin.domain.dto;

import lombok.Data;

@Data
public class FileVerifyDTO {
    private String identifier;   // 文件的 SHA-256 指纹
    private String filename;     // 文件名
    private Long totalSize;      // 文件总大小
    private String parentId;     // 当前所在的文件夹 ID，例如 f_1 或 root
    private Long chunkSize;      // 每个分片的大小
    private Integer totalChunks; // 分片总数
}