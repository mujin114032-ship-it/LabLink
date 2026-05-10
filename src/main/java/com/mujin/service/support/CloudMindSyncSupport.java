package com.mujin.service.support;

import com.mujin.client.CloudMindClient;
import com.mujin.domain.dto.cloudmind.CloudMindFileDeleteSyncRequest;
import com.mujin.domain.dto.cloudmind.CloudMindFileSyncRequest;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUser;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudMindSyncSupport {

    private final CloudMindClient cloudMindClient;

    private final AgentFileTypeSupport agentFileTypeSupport;

    private final UserMapper userMapper;

    public void syncFileAfterCommit(SysUserFile userFile, SysFile physicalFile) {
        if (userFile == null || physicalFile == null) {
            return;
        }

        if ("1".equals(userFile.getIsDir())) {
            return;
        }

        if (userFile.getId() == null || userFile.getUserId() == null) {
            return;
        }

        if (!agentFileTypeSupport.isSupported(userFile.getFileName())) {
            log.info("文件类型不支持进入 CloudMind，跳过同步：fileName={}", userFile.getFileName());
            return;
        }

        SysUser user = userMapper.selectById(userFile.getUserId());
        if (user == null) {
            log.warn("CloudMind 同步跳过：用户不存在，userId={}", userFile.getUserId());
            return;
        }

        CloudMindFileSyncRequest request = new CloudMindFileSyncRequest();
        request.setLabLinkUserId(String.valueOf(userFile.getUserId()));
        request.setUsername(user.getUsername());
        request.setLabLinkFileId("f_" + userFile.getId());
        request.setFileName(userFile.getFileName());
        request.setFileType(agentFileTypeSupport.extractFileType(userFile.getFileName()));
        request.setFileSize(physicalFile.getFileSize());
        request.setContentType(agentFileTypeSupport.guessContentType(userFile.getFileName()));
        request.setObjectKey(physicalFile.getFilePath());
        request.setSha256(physicalFile.getFileIdentifier());

        runAfterCommit(() -> cloudMindClient.syncFile(request));
    }

    public void deleteSyncAfterCommit(SysUserFile userFile, String deleteReason) {
        if (userFile == null) {
            return;
        }

        if ("1".equals(userFile.getIsDir())) {
            return;
        }

        if (userFile.getId() == null || userFile.getUserId() == null) {
            return;
        }

        SysUser user = userMapper.selectById(userFile.getUserId());
        if (user == null) {
            log.warn("CloudMind 删除同步跳过：用户不存在，userId={}", userFile.getUserId());
            return;
        }

        CloudMindFileDeleteSyncRequest request = new CloudMindFileDeleteSyncRequest();
        request.setLabLinkUserId(String.valueOf(userFile.getUserId()));
        request.setUsername(user.getUsername());
        request.setLabLinkFileId("f_" + userFile.getId());
        request.setDeleteReason(deleteReason == null ? "lablink_delete" : deleteReason);

        runAfterCommit(() -> cloudMindClient.deleteSync(request));
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}