package com.mujin.service;

import com.mujin.domain.dto.*;
import com.mujin.domain.vo.FileVerifyVO;
import com.mujin.domain.vo.ShareLinkVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface FileService {
    Map<String, Object> getFileList(FileQueryDTO fileQueryDTO);

    String upload(MultipartFile file, String parentId, Long userId);

    String createFolder(FolderCreateDTO dto, Long userId);

    void rename(FileRenameDTO dto, Long userId);

    void download(String id, Long userId, String role, HttpServletResponse response);

    void moveFiles(FileMoveDTO dto, Long userId, String role);

    void deleteFiles(List<String> ids, Long userId, String role);

    ShareLinkVO shareFiles(FileShareDTO dto, Long userId);

    boolean uploadChunk(ChunkUploadDTO dto);

    String mergeChunks(FileMergeDTO dto, Long userId);

    FileVerifyVO verifyFile(FileVerifyDTO dto, Long userId);
}
