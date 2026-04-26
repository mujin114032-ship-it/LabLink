package com.mujin.domain.vo;
import lombok.Data;

@Data
public class RecycleVO {
    // 回收站文件信息
    private String id;
    private String name;
    private String type;
    private Long size;
    private String deleteTime;
    private Integer daysLeft;
}