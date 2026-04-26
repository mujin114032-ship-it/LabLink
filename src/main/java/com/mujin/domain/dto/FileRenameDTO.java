package com.mujin.domain.dto;

import lombok.Data;

@Data
public class FileRenameDTO {
    private String id;      // 前端传来的逻辑 ID，如 "f_1"
    private String newName; // 新文件名
}