package com.mujin.domain.dto;
import lombok.Data;
import java.util.List;

@Data
public class FileShareDTO {
    private List<String> ids;      // 要分享的文件 ID 列表
    private Integer expireDays;    // 过期天数 (1, 7, 30, 0表示永久)
}