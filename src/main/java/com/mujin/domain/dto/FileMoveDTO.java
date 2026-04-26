package com.mujin.domain.dto;

import lombok.Data;
import java.util.List;

@Data
public class FileMoveDTO {
    // 接收前端传来的逻辑 ID 列表，例如 ["f_1001", "f_1002"]
    private List<String> ids;

    // 目标父级目录 ID，例如 "f_3005" 或者 "root"
    private String targetParentId;
}
