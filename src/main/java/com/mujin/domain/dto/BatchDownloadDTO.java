package com.mujin.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchDownloadDTO {
    private List<String> ids;
}