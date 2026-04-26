package com.mujin.service;

import com.mujin.domain.dto.ShareSaveDTO;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

public interface ShareService {
    Map<String, Object> getShareList(Integer page, Integer pageSize, String keyword);

    void saveToMyFiles(ShareSaveDTO dto, Long userId);

    void downloadShareFile(String shareId, HttpServletResponse response);

    void deleteShares(List<String> shareIds);
}
