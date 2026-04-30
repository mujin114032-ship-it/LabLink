package com.mujin.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileDownloadUrlVO {

    /**
     * MinIO 预签名下载地址
     */
    private String url;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * URL 有效期，单位：秒
     */
    private Integer expireSeconds;
}
