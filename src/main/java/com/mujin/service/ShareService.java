package com.mujin.service;

import com.mujin.domain.dto.ShareSaveDTO;
import com.mujin.domain.vo.FileDownloadUrlVO;
import com.mujin.domain.vo.PublicShareVO;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

public interface ShareService {
    Map<String, Object> getShareList(Integer page, Integer pageSize, String keyword);

    void saveToMyFiles(ShareSaveDTO dto, Long userId);

    void downloadShareFile(String shareId, HttpServletResponse response);

    void deleteShares(List<String> shareIds);

    PublicShareVO getPublicShare(String shareCode);

    FileDownloadUrlVO getPublicShareDownloadUrl(String shareCode, String fileId);

    void directDownloadShare(String shareCode, HttpServletResponse response);
}
