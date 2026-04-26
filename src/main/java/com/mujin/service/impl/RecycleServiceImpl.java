package com.mujin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mujin.domain.dto.FileQueryDTO;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.RecycleVO;
import com.mujin.mapper.FileMapper;
import com.mujin.mapper.ShareMapper;
import com.mujin.service.RecycleService;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecycleServiceImpl implements RecycleService {

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ShareMapper shareMapper;

    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 获取回收站列表
     */
    @Override
    public Map<String, Object> getRecycleList(FileQueryDTO dto) {
        PageHelper.startPage(dto.getPage(), dto.getPageSize());
        List<RecycleVO> list = fileMapper.selectRecycleList(dto.getUserId());
        PageInfo<RecycleVO> pageInfo = new PageInfo<>(list);

        Map<String, Object> result = new HashMap<>();
        result.put("list", pageInfo.getList());
        result.put("total", pageInfo.getTotal());
        result.put("current", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        return result;
    }

    /**
     * 还原文件
     * @param ids
     * @param userId
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void restoreFiles(List<String> ids, Long userId) {
        List<Long> numericIds = parseIds(ids);
        if (numericIds.isEmpty()) return;
        // 还原就是把 is_deleted 改回 0
        fileMapper.updateFileStatus(numericIds, userId, 0);
    }

    /**
     * 彻底删除
     * @param ids
     * @param userId
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void hardDeleteFiles(List<String> ids, Long userId) {
        List<Long> numericIds = parseIds(ids);
        if (numericIds.isEmpty()) return;

        // 1. 查询要删除的逻辑文件记录
        List<SysUserFile> userFiles = fileMapper.selectUserFilesByIds(numericIds, userId);

        for (SysUserFile userFile : userFiles) {

            // 撤回该文件在共享空间的所有分享记录
            shareMapper.deleteShareByFileId(userFile.getId());
            // 2. 从逻辑表物理删除
            fileMapper.deleteUserFileById(userFile.getId());

            // 3. 如果是文件（非文件夹），需要处理物理存储和容量
            if ("0".equals(userFile.getIsDir()) && userFile.getFileId() != null && userFile.getFileId() != 0) {
                SysFile physicalFile = fileMapper.selectSysFileById(userFile.getFileId());

                if (physicalFile != null) {
                    // 释放用户的存储容量 (回收站里的文件依然占空间，彻底删除才释放)
                    fileMapper.decreaseUsedStorage(userId, physicalFile.getFileSize());

                    // 引用计数！检查全网是否还有其他逻辑文件指向这个物理文件
                    int refCount = fileMapper.countByFileId(physicalFile.getId());
                    if (refCount == 0) {
                        // 判断没有引用之后再删除物理文件
                        fileMapper.deleteSysFileById(physicalFile.getId());
                        try {
                            minioClient.removeObject(
                                    RemoveObjectArgs.builder()
                                            .bucket(bucketName)
                                            .object(physicalFile.getFilePath())
                                            .build()
                            );
                            log.info("物理文件已从 MinIO 清除: {}", physicalFile.getFilePath());
                        } catch (Exception e) {
                            log.error("MinIO 文件删除失败", e);
                        }
                    } else {
                        log.info("文件存在秒传引用，保留 MinIO 物理对象: {}", physicalFile.getFilePath());
                    }
                }
            }
        }
    }

    /**
     * 清空回收站
     * @param userId
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void clearRecycleBin(Long userId) {
        // 先查出该用户回收站里的所有文件 ID
        List<Long> allDeletedIds = fileMapper.selectAllDeletedIdsByUser(userId);
        if (!allDeletedIds.isEmpty()) {
            // 复用格式化 ID 的逻辑，直接调用彻底删除
            List<String> strIds = allDeletedIds.stream().map(id -> "f_" + id).collect(Collectors.toList());
            hardDeleteFiles(strIds, userId);
        }
    }

    // 辅助方法：解析 ID
    private List<Long> parseIds(List<String> ids) {
        List<Long> numericIds = new ArrayList<>();
        if (ids == null) return numericIds;
        for (String idStr : ids) {
            if (idStr != null && idStr.startsWith("f_")) {
                try {
                    numericIds.add(Long.valueOf(idStr.substring(2)));
                } catch (Exception ignored) {}
            }
        }
        return numericIds;
    }
}
