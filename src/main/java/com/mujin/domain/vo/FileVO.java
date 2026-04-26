package com.mujin.domain.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FileVO {
    private String id;        // 对应逻辑表 sys_user_file 的 id
    private String name;      // 文件名
    private String type;      // folder, image, doc, video, etc.
    private Long size;        // 字节数
    private String updateTime; // 格式化后的时间
    private Boolean isDir;    // 是否为文件夹
    private String parentId;  // 父文件夹ID
}