package com.mujin.domain.dto;
import lombok.Data;

@Data
public class ShareSaveDTO {
    private String shareId;       // sh_xxx
    private String targetParentId; // 目标父目录 ID (例如 f_101 或 root)
}
