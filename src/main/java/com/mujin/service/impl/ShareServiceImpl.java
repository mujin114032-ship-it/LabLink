package com.mujin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mujin.domain.dto.ShareSaveDTO;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.FileDownloadUrlVO;
import com.mujin.domain.vo.PublicShareFileVO;
import com.mujin.domain.vo.PublicShareVO;
import com.mujin.domain.vo.ShareVO;
import com.mujin.mapper.FileMapper;
import com.mujin.mapper.ShareMapper;
import com.mujin.service.ShareService;
import com.mujin.service.support.MinioFileSupport;
import com.mujin.service.support.StorageQuotaSupport;
import io.minio.*;
import io.minio.http.Method;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.mujin.utils.FileNameUtils.buildUniqueZipEntryName;

@Slf4j
@Service
public class ShareServiceImpl implements ShareService {

    @Autowired
    private ShareMapper shareMapper;
    @Autowired
    private FileMapper fileMapper;
    @Autowired
    private MinioClient minioClient;
    @Autowired
    private StorageQuotaSupport storageQuotaSupport;
    @Autowired
    private MinioFileSupport minioFileSupport;
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
            storageQuotaSupport.deductOrThrow(userId, physicalFile.getFileSize());
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

    /**
     * 获取公开分享详情
     */
    @Override
    public PublicShareVO getPublicShare(String shareCode) {
        if (shareCode == null || shareCode.trim().isEmpty()) {
            throw new RuntimeException("分享码不能为空");
        }

        LocalDateTime expireTime = shareMapper.selectShareExpireTimeByCode(shareCode);
        List<PublicShareFileVO> files = shareMapper.selectPublicShareFilesByCode(shareCode);

        if (files == null || files.isEmpty()) {
            throw new RuntimeException("分享不存在、已过期或已被取消");
        }

        PublicShareVO vo = new PublicShareVO();
        vo.setShareCode(shareCode);
        vo.setExpireTime(expireTime);
        vo.setExpired(false);
        vo.setFiles(files);

        return vo;
    }

    /**
     * 获得公开下载链接方法
     */
    @Override
    public FileDownloadUrlVO getPublicShareDownloadUrl(String shareCode, String fileId) {
        try {
            if (shareCode == null || shareCode.trim().isEmpty()) {
                throw new RuntimeException("分享码不能为空");
            }

            if (fileId == null || !fileId.startsWith("f_")) {
                throw new RuntimeException("文件 ID 格式错误");
            }

            Long numericFileId = Long.valueOf(fileId.substring(2));

            // 1. 校验：该 fileId 必须属于该 shareCode，且分享未过期、未取消
            SysUserFile originFile = shareMapper.selectPublicShareFileForDownload(shareCode, numericFileId);

            if (originFile == null) {
                throw new RuntimeException("分享不存在、文件不属于该分享或分享已过期");
            }

            if ("1".equals(originFile.getIsDir())) {
                throw new RuntimeException("暂不支持直接下载文件夹");
            }

            // 2. 查询物理文件
            SysFile physicalFile = fileMapper.selectSysFileById(originFile.getFileId());

            if (physicalFile == null || physicalFile.getFilePath() == null) {
                throw new RuntimeException("底层物理文件已丢失");
            }

            // 3. 计算预签名 URL 有效期
            int expireSeconds = calculatePublicShareDownloadExpireSeconds(shareCode);

            // 4. 设置 MinIO 下载响应参数
            String encodedFileName = URLEncoder
                    .encode(originFile.getFileName(), StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            Map<String, String> reqParams = new HashMap<>();
            reqParams.put("response-content-type", "application/octet-stream");
            reqParams.put(
                    "response-content-disposition",
                    "attachment; filename*=UTF-8''" + encodedFileName
            );

            // 5. 签发预签名 URL
            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(physicalFile.getFilePath())
                            .expiry(expireSeconds, TimeUnit.SECONDS)
                            .extraQueryParams(reqParams)
                            .build()
            );

            return new FileDownloadUrlVO(url, originFile.getFileName(), expireSeconds);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成公开分享下载链接失败，shareCode={}, fileId={}", shareCode, fileId, e);
            throw new RuntimeException("生成分享下载链接失败，请稍后重试");
        }
    }

    /**
     * 公开分享下载链接有效期：
     * 1. 永久分享：下载 URL 最多 1 天有效；
     * 2. 有限期分享：下载 URL 有效期不能超过分享剩余时间，且最多 1 天；
     * 3. 剩余时间太短时，直接认为分享即将过期。
     */
    private int calculatePublicShareDownloadExpireSeconds(String shareCode) {
        int oneDaySeconds = 24 * 60 * 60;

        LocalDateTime expireTime = shareMapper.selectShareExpireTimeByCode(shareCode);

        // 永久分享：预签名 URL 1 天有效
        if (expireTime == null) {
            return oneDaySeconds;
        }

        long remainSeconds = Duration.between(LocalDateTime.now(), expireTime).getSeconds();

        if (remainSeconds <= 0) {
            throw new RuntimeException("分享已过期");
        }

        // 如果只剩几十秒，不建议继续签发，避免前端刚拿到就失效
        if (remainSeconds < 60) {
            throw new RuntimeException("分享即将过期，请联系分享者重新生成链接");
        }

        return (int) Math.min(oneDaySeconds, remainSeconds);
    }

    /**
     * 直接下载公开分享文件
     */
    @Override
    public void directDownloadShare(String shareCode, HttpServletResponse response) {
        try {
            // 1. 查询 shareCode 下的有效分享文件
            List<SysUserFile> files = shareMapper.selectValidShareUserFilesByCode(shareCode);

            if (files == null || files.isEmpty()) {
                writeSimpleHtml(response, "分享已失效", "该分享不存在、已过期或已被取消。");
                return;
            }

            // 2. 过滤文件夹
            List<SysUserFile> normalFiles = files.stream()
                    .filter(file -> "0".equals(file.getIsDir()))
                    .toList();

            if (normalFiles.isEmpty()) {
                writeSimpleHtml(response, "暂不支持下载文件夹", "该分享中没有可直接下载的文件。");
                return;
            }

            String presignedUrl;

            if (normalFiles.size() == 1) {
                // 单文件：直接生成该文件的预签名 URL
                presignedUrl = generatePublicShareFilePresignedUrl(shareCode, normalFiles.get(0));
            } else {
                // 多文件：打包成 ZIP，然后生成 ZIP 的预签名 URL
                presignedUrl = generatePublicShareZipPresignedUrl(shareCode, normalFiles);
            }

            // 3. 302 重定向到 MinIO 预签名 URL
            response.sendRedirect(presignedUrl);

        } catch (Exception e) {
            log.error("公开分享直达下载失败，shareCode={}", shareCode, e);
            writeSimpleHtml(response, "下载失败", "文件下载失败，请稍后重试。");
        }
    }

    private void writeSimpleHtml(HttpServletResponse response, String title, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html;charset=UTF-8");

            String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
                            background: #f5f7fa;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                            margin: 0;
                        }
                        .card {
                            background: white;
                            padding: 32px 40px;
                            border-radius: 12px;
                            box-shadow: 0 6px 24px rgba(0,0,0,0.08);
                            text-align: center;
                        }
                        h3 { margin: 0 0 12px; color: #333; }
                        p { margin: 0; color: #666; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h3>%s</h3>
                        <p>%s</p>
                    </div>
                </body>
                </html>
                """.formatted(title, title, message);

            response.getWriter().write(html);
            response.getWriter().flush();

        } catch (Exception ignored) {
        }
    }

    /**
     * 单文件公开分享：生成该文件的 MinIO 预签名下载 URL。
     */
    private String generatePublicShareFilePresignedUrl(String shareCode, SysUserFile originFile) {
        try {
            if (originFile == null || "1".equals(originFile.getIsDir())) {
                throw new RuntimeException("分享文件不存在或不支持下载文件夹");
            }

            SysFile physicalFile = fileMapper.selectSysFileById(originFile.getFileId());

            if (physicalFile == null || physicalFile.getFilePath() == null) {
                throw new RuntimeException("底层物理文件已丢失");
            }

            int expireSeconds = calculatePublicShareDownloadExpireSeconds(shareCode);

            String encodedFileName = URLEncoder
                    .encode(originFile.getFileName(), StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            Map<String, String> reqParams = new HashMap<>();
            reqParams.put("response-content-type", "application/octet-stream");
            reqParams.put(
                    "response-content-disposition",
                    "attachment; filename*=UTF-8''" + encodedFileName
            );

            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(physicalFile.getFilePath())
                            .expiry(expireSeconds, TimeUnit.SECONDS)
                            .extraQueryParams(reqParams)
                            .build()
            );

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成单文件公开分享预签名 URL 失败，shareCode={}, fileId={}",
                    shareCode, originFile == null ? null : originFile.getId(), e);
            throw new RuntimeException("生成分享下载链接失败，请稍后重试");
        }
    }

    /**
     * 多文件公开分享：将多个文件打包为临时 ZIP，再生成 ZIP 的 MinIO 预签名下载 URL。
     */
    private String generatePublicShareZipPresignedUrl(String shareCode, List<SysUserFile> normalFiles) {
        if (normalFiles == null || normalFiles.isEmpty()) {
            throw new RuntimeException("分享中没有可下载的文件");
        }

        Path tempZipPath = null;
        String zipObjectName = null;

        try {
            String zipFileName = "LabLinkAI_分享文件_" + System.currentTimeMillis() + ".zip";
            tempZipPath = Files.createTempFile("lablink-share-download-", ".zip");

            Set<String> usedEntryNames = new HashSet<>();

            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(tempZipPath))) {
                for (SysUserFile userFile : normalFiles) {
                    if (userFile == null || !"0".equals(userFile.getIsDir())) {
                        continue;
                    }

                    SysFile physicalFile = fileMapper.selectSysFileById(userFile.getFileId());

                    if (physicalFile == null || physicalFile.getFilePath() == null) {
                        log.warn("公开分享 ZIP 跳过物理文件缺失项，logicId={}, fileId={}",
                                userFile.getId(), userFile.getFileId());
                        continue;
                    }

                    String entryName = buildUniqueZipEntryName(userFile.getFileName(), usedEntryNames);

                    zipOut.putNextEntry(new ZipEntry(entryName));

                    try (InputStream inputStream = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(physicalFile.getFilePath())
                                    .build()
                    )) {
                        inputStream.transferTo(zipOut);
                    }

                    zipOut.closeEntry();
                }
            }

            if (Files.size(tempZipPath) == 0) {
                throw new RuntimeException("没有可打包的文件");
            }

            zipObjectName = "temp/batch-download/"
                    + UUID.randomUUID().toString().replace("-", "")
                    + ".zip";

            try (InputStream zipInputStream = Files.newInputStream(tempZipPath)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(zipObjectName)
                                .stream(zipInputStream, Files.size(tempZipPath), -1)
                                .contentType("application/zip")
                                .build()
                );
            }

            int expireSeconds = calculatePublicShareDownloadExpireSeconds(shareCode);

            String encodedFileName = URLEncoder
                    .encode(zipFileName, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            Map<String, String> reqParams = new HashMap<>();
            reqParams.put("response-content-type", "application/zip");
            reqParams.put(
                    "response-content-disposition",
                    "attachment; filename*=UTF-8''" + encodedFileName
            );

            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(zipObjectName)
                            .expiry(expireSeconds, TimeUnit.SECONDS)
                            .extraQueryParams(reqParams)
                            .build()
            );

        } catch (RuntimeException e) {
            if (zipObjectName != null) {
                minioFileSupport.deleteTempZipQuietly(zipObjectName);
            }
            throw e;

        } catch (Exception e) {
            if (zipObjectName != null) {
                minioFileSupport.deleteTempZipQuietly(zipObjectName);
            }

            log.error("公开分享 ZIP 打包失败，shareCode={}", shareCode, e);
            throw new RuntimeException("分享文件打包失败，请稍后重试");

        } finally {
            if (tempZipPath != null) {
                try {
                    Files.deleteIfExists(tempZipPath);
                } catch (Exception e) {
                    log.warn("删除公开分享本地临时 ZIP 失败，path={}", tempZipPath, e);
                }
            }
        }
    }

}
