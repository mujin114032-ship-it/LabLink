package com.mujin.domain.vo;
import lombok.Data;
import java.util.List;

@Data
public class FileVerifyVO {
    // 是否需要继续上传？ (false 表示触发了秒传，true 表示需要上传分片)
    private Boolean shouldUpload;
    // 已经上传成功的分片序号集合 (用于断点续传，前端会跳过这些序号)
    private List<Integer> uploadedChunks;
}