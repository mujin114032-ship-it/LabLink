package com.mujin.domain.dto;

import lombok.Data;

@Data
public class FolderCreateDTO {
    private String parentId = "root"; // 默认在根目录
    private String name;              // 文件夹名称
}
