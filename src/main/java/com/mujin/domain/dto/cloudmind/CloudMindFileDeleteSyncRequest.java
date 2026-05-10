package com.mujin.domain.dto.cloudmind;

import lombok.Data;

@Data
public class CloudMindFileDeleteSyncRequest {

    private String labLinkUserId;

    private String username;

    private String labLinkFileId;

    private String deleteReason;
}