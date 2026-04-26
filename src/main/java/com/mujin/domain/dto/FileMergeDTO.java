package com.mujin.domain.dto;
import lombok.Data;

@Data
public class FileMergeDTO {
    private String identifier;  // 文件的 MD5 指纹
    private String filename;    // 原始文件名
    private Integer totalChunks;// 分片总数
    private String parentId;    // 保存到的父目录 ID
}