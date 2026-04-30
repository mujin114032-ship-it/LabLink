package com.mujin.assembler;

import com.mujin.domain.vo.FileVerifyVO;
import com.mujin.utils.UploadProgressUtils;

import java.util.List;

public class FileVerifyAssembler {

    private FileVerifyAssembler() {}

    public static FileVerifyVO build(
            String status,
            Boolean shouldUpload,
            List<Integer> uploadedChunks,
            Integer totalChunks,
            String uploadSessionId,
            Integer pollInterval,
            String message
    ) {
        FileVerifyVO vo = new FileVerifyVO();

        int uploadedCount = uploadedChunks == null ? 0 : uploadedChunks.size();

        vo.setStatus(status);
        vo.setShouldUpload(shouldUpload);
        vo.setUploadedChunks(uploadedChunks);
        vo.setTotalChunks(totalChunks);
        vo.setUploadedCount(uploadedCount);
        vo.setProgress(UploadProgressUtils.calculateProgress(uploadedChunks, totalChunks));
        vo.setUploadSessionId(uploadSessionId);
        vo.setPollInterval(pollInterval);
        vo.setMessage(message);

        return vo;
    }
}