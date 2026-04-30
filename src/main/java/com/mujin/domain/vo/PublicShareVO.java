package com.mujin.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PublicShareVO {
    private String shareCode;
    private LocalDateTime expireTime;
    private Boolean expired;
    private List<PublicShareFileVO> files;
}