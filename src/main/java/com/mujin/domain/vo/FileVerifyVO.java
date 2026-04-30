package com.mujin.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class FileVerifyVO {

    /**
     * 是否需要当前用户上传分片
     */
    private Boolean shouldUpload;

    /**
     * 已上传分片编号
     */
    private List<Integer> uploadedChunks;

    /**
     * 上传状态：
     * INSTANT_SUCCESS：物理文件已存在，秒传成功
     * CAN_UPLOAD：当前用户可以上传
     * WAITING_UPLOAD：其他用户正在上传，当前用户等待共享进度
     * CAN_TAKEOVER：原上传会话超时，当前用户可以接管续传
     */
    private String status;

    /**
     * 上传进度，0~100
     */
    private Integer progress;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 已上传分片数
     */
    private Integer uploadedCount;

    /**
     * 当前上传会话ID，只有 CAN_UPLOAD / CAN_TAKEOVER 时返回
     */
    private String uploadSessionId;

    /**
     * 建议前端轮询间隔，单位毫秒
     */
    private Integer pollInterval;

    /**
     * 前端展示信息
     */
    private String message;
}