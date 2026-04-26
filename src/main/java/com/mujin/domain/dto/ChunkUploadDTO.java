package com.mujin.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChunkUploadDTO {
    // 文件的唯一标识
    private String identifier;
    // 文件的原始名称
    private String filename;
    // 当前分片的索引（从 1 开始）
    private Integer chunkNumber;
    // 总分片数
    private Integer totalChunks;
    // 当前分片的二进制文件数据
    private MultipartFile file;
}