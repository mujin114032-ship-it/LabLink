package com.mujin.domain.vo;
import lombok.Data;

@Data
public class ShareVO {
    private String id;        // 格式 sh_xxx
    private String name;
    private String owner;     // 分享者昵称
    private String shareTime;
    private String expires;   // 过期日期
    private Long size;
    private String type;
}
