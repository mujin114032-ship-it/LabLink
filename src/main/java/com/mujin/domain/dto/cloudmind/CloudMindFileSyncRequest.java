package com.mujin.domain.dto.cloudmind;

import lombok.Data;

@Data
public class CloudMindFileSyncRequest {

    private String labLinkUserId;

    private String username;

    private String labLinkFileId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    private String contentType;

    private String objectKey;

    private String sha256;
}