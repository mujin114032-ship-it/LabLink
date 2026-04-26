package com.mujin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mujin.domain.dto.ShareSaveDTO;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.ShareVO;
import com.mujin.mapper.FileMapper;
import com.mujin.mapper.ShareMapper;
import com.mujin.service.ShareService;
import io.minio.MinioClient;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ShareServiceImpl implements ShareService {

    @Autowired
    private ShareMapper shareMapper;
    @Autowired
    private FileMapper fileMapper;
    @Autowired
    private MinioClient minioClient;
    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 获取共享文件列表
     * @param page
     * @param pageSize
     * @param keyword
     * @return
     */
    @Override
    public Map<String, Object> getShareList(Integer page, Integer pageSize, String keyword) {
        PageHelper.startPage(page, pageSize);
        List<ShareVO> list = shareMapper.selectShareList(keyword);
        PageInfo<ShareVO> pageInfo = new PageInfo<>(list);

        Map<String, Object> result = new HashMap<>();
        result.put("list", pageInfo.getList());
        result.put("total", pageInfo.getTotal());
        result.put("current", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        return result;
    }

    /**
     * 转存共享文件至个人网盘
     * @param dto
     * @param userId
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveToMyFiles(ShareSaveDTO dto, Long userId) {
        // 解析 shareId (sh_10 -> 10)
        Long shareId = Long.valueOf(dto.getShareId().substring(3));

        // 查询分享记录及原始文件信息
        SysUserFile originFile = shareMapper.getOriginFileByShareId(shareId);
        if (originFile == null) throw new RuntimeException("分享已失效或文件不存在");

        // 创建一条全新的逻辑记录指向同一个物理文件
        SysUserFile newFile = new SysUserFile();
        newFile.setUserId(userId);
        newFile.setFileId(originFile.getFileId());
        newFile.setParentId(dto.getTargetParentId());
        newFile.setFileName(originFile.getFileName());
        newFile.setIsDir(originFile.getIsDir());

        // 插入逻辑表
        fileMapper.insertSysUserFile(newFile);

        // 转存的人也要占网盘容量！
        if ("0".equals(originFile.getIsDir())) {
            SysFile physicalFile = fileMapper.selectSysFileById(originFile.getFileId());
            fileMapper.increaseUsedStorage(userId, physicalFile.getFileSize());
        }

        log.info("用户 {} 转存了分享文件 {}, 逻辑ID为 {}", userId, originFile.getFileName(), newFile.getId());
    }

    /**
     * 下载共享文件
     * @param shareId
     * @param response
     */
    @Override
    public void downloadShareFile(String shareId, HttpServletResponse response) {
        try {
            // 1. 解析 shareId (从 "sh_10" 提取 10)
            Long numericShareId = Long.valueOf(shareId.substring(3));

            // 2. 查出这条分享记录对应的原始逻辑文件
            SysUserFile originFile = shareMapper.getOriginFileByShareId(numericShareId);
            if (originFile == null || "1".equals(originFile.getIsDir())) {
                throw new RuntimeException("分享已失效或不支持直接下载文件夹");
            }

            // 3. 查出底层的物理文件路径
            SysFile physicalFile = fileMapper.selectSysFileById(originFile.getFileId());
            if (physicalFile == null) {
                throw new RuntimeException("底层物理文件已丢失");
            }

            // 4. 设置响应头，解决中文名乱码
            String encodedFileName = java.net.URLEncoder.encode(originFile.getFileName(), java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
            response.setContentType("application/octet-stream");

            // 5. 从 MinIO 抽取文件流并打给前端
            try (java.io.InputStream minioStream = minioClient.getObject(
                    io.minio.GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(physicalFile.getFilePath())
                            .build()
            )) {
                org.springframework.util.StreamUtils.copy(minioStream, response.getOutputStream());
                response.flushBuffer();
            }

        } catch (Exception e) {
            log.error("共享文件下载失败", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 管理员批量删除分享记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteShares(List<String> shareIds) {
        if (shareIds == null || shareIds.isEmpty()) {
            throw new RuntimeException("未选择要清理的分享记录");
        }

        // 1. 解析 ID (把 "sh_10", "sh_11" 变成 10, 11)
        List<Long> numericShareIds = new java.util.ArrayList<>();
        for (String idStr : shareIds) {
            if (idStr != null && idStr.startsWith("sh_")) {
                numericShareIds.add(Long.valueOf(idStr.substring(3)));
            }
        }

        if (numericShareIds.isEmpty()) return;

        // 2. 批量从 sys_share 表中删除
        shareMapper.batchDeleteShares(numericShareIds);

        log.info("管理员修改了共享空间，删除了 {} 条分享", numericShareIds.size());
    }
}
