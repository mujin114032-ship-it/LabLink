package com.mujin.domain.dto;

import lombok.Data;

@Data
public class FileQueryDTO {
    // 代表“目标用户 ID”
    private Long userId;

    // 父文件夹ID，默认 root
    private String parentId = "root";

    // 分类：all/image/doc/video/audio/other
    private String category = "all";

    // 文件名模糊搜索
    private String keyword;

    // 页码，默认 1
    private Integer page = 1;

    // 每页条数，默认 10
    private Integer pageSize = 10;
}