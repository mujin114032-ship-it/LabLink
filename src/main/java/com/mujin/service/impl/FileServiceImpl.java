package com.mujin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mujin.assembler.FileVerifyAssembler;
import com.mujin.domain.dto.*;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUser;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.FileDownloadUrlVO;
import com.mujin.domain.vo.FileVO;
import com.mujin.domain.vo.FileVerifyVO;
import com.mujin.domain.vo.ShareLinkVO;
import com.mujin.mapper.FileMapper;
import com.mujin.mapper.ShareMapper;
import com.mujin.mapper.UserMapper;
import com.mujin.service.FileService;
import com.mujin.service.support.MinioFileSupport;
import com.mujin.service.support.StorageQuotaSupport;
import com.mujin.service.support.UploadRedisSupport;
import io.minio.*;
import io.minio.http.Method;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.mujin.utils.FileHashUtils.calculateSha256;
import static com.mujin.utils.FileNameUtils.generateObjectName;
import static com.mujin.utils.UploadProgressUtils.calculateProgress;


@Service
@Slf4j
public class FileServiceImpl implements FileService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private ShareMapper shareMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UploadRedisSupport uploadRedisSupport;

    @Autowired
    private MinioFileSupport minioFileSupport;

    @Autowired
    private StorageQuotaSupport storageQuotaSupport;

    @Value("${minio.bucketName}")
    private String bucketName;

    // 上传状态
    private static final String UPLOAD_STATUS_INSTANT_SUCCESS = "INSTANT_SUCCESS";
    private static final String UPLOAD_STATUS_CAN_UPLOAD = "CAN_UPLOAD";
    private static final String UPLOAD_STATUS_WAITING_UPLOAD = "WAITING_UPLOAD";
    private static final String UPLOAD_STATUS_CAN_TAKEOVER = "CAN_TAKEOVER";

    // 上传元数据前缀
    // upload:meta:{identifier}   记录同一个文件的 totalSize / chunkSize / totalChunks
    // upload:owner:{identifier}  当前负责上传该文件的 uploadSessionId
    // chunk_progress:{identifier}  已上传分片集合
    // chunk_meta:{identifier}  分片元数据，记录分片的 start / end / size / md5
    // upload:active  当前是否有上传任务在进行
    private static final String UPLOAD_META_PREFIX = "upload:meta:";
    private static final String UPLOAD_OWNER_PREFIX = "upload:owner:";
    private static final String CHUNK_PROGRESS_PREFIX = "chunk_progress:";
    private static final String CHUNK_META_PREFIX = "chunk_meta:";
    private static final String UPLOAD_ACTIVE_KEY = "upload:active";

    // 上传元数据过期时间，单位秒
    private static final long UPLOAD_OWNER_TTL_SECONDS = 120L;
    private static final int DEFAULT_POLL_INTERVAL = 2000;

    // merge 阶段可能稍微耗时，进入 merge 前把 owner 租约延长到 10 分钟。
    private static final long UPLOAD_MERGE_TTL_SECONDS = 10L * 60;

    /**
     * 查询文件列表
     */
    @Override
    public Map<String, Object> getFileList(FileQueryDTO fileQueryDTO) {
        // 1. 开启分页
        PageHelper.startPage(fileQueryDTO.getPage(), fileQueryDTO.getPageSize());
        // 2. 执行查询
        List<FileVO> list = fileMapper.selectFileList(fileQueryDTO);
        PageInfo<FileVO> pageInfo = new PageInfo<>(list);

        // 3. 封装符合接口文档的返回格式
        Map<String, Object> result = new HashMap<>();
        result.put("list", pageInfo.getList());
        result.put("total", pageInfo.getTotal());
        result.put("current", pageInfo.getPageNum());
        result.put("pageSize", pageInfo.getPageSize());
        return result;
    }

    /**
     * 上传文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String upload(MultipartFile file, String parentId, Long userId) {
        try {
            // ================= 1. 容量校验 =================
            SysUser user = userMapper.selectById(userId);
            if (user == null) throw new RuntimeException("用户不存在");

            long fileSize = file.getSize();
            if (user.getUsedStorage() + fileSize > user.getTotalStorage()) {
                throw new RuntimeException("存储空间不足，请清理后重试");
            }

            // ================= 2. 计算 SHA-256 文件指纹，用于秒传与去重 =================
            String identifier = calculateSha256(file.getInputStream());

            // ================= 3. 物理文件查重与上传 =================
            SysFile physicalFile = fileMapper.selectSysFileByIdentifier(identifier);
            Long physicalFileId;

            if (physicalFile != null) {
                // 触发秒传：数据库里已经有这个物理文件了，直接复用 ID
                physicalFileId = physicalFile.getId();
            } else {
                // 真实上传：存入 MinIO
                String originalName = file.getOriginalFilename();
                // 生成在 MinIO 中的存储路径，例如：2026/04/12/xxxx.pdf
                String objectName = generateObjectName(originalName, identifier);

                // 调用 MinIO SDK 上传文件
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(file.getInputStream(), fileSize, -1) // -1 表示由 SDK 自动处理分片大小
                                .contentType(file.getContentType())
                                .build()
                );

                // 记录到物理文件表 sys_file
                SysFile newSysFile = new SysFile();
                newSysFile.setFileIdentifier(identifier);
                newSysFile.setFileSize(fileSize);
                newSysFile.setFilePath(objectName);
                fileMapper.insertSysFile(newSysFile); // 插入后 MyBatis 需回填自增 ID

                physicalFileId = newSysFile.getId();
            }

            // ================= 4. 建立逻辑关联 =================
            // 创建一个实体对象
            SysUserFile userFile = new SysUserFile();
            userFile.setUserId(userId);
            userFile.setFileId(physicalFileId);
            userFile.setParentId(parentId);
            userFile.setFileName(file.getOriginalFilename());
            userFile.setIsDir("0");

            // 插入表。执行完这行后，MyBatis 会把 MySQL 自增的数字塞进 userFile.getId() 里
            fileMapper.insertSysUserFile(userFile);

            // 拿到刚生成的数字 ID，拼接成前端需要的字符串格式
            String logicFileId = "f_" + userFile.getId();

            // ================= 5. 扣减用户网盘容量 =================
            // 使用 Redisson 用户锁 + MySQL 条件更新，避免并发超扣容量
            storageQuotaSupport.deductOrThrow(userId, fileSize);

            return logicFileId;

        } catch (Exception e) {
            // 捕获异常并抛出，触发 @Transactional 事务回滚
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 新建文件夹
     */
    @Override
    public String createFolder(FolderCreateDTO dto, Long userId) {
        // 1. 基础校验
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new RuntimeException("文件夹名称不能为空");
        }

        // 2. 创建逻辑文件对象
        SysUserFile folder = new SysUserFile();
        folder.setUserId(userId);
        // 文件夹没有物理实体，所以关联的物理文件ID设为 0
        folder.setFileId(0L);
        folder.setParentId(dto.getParentId());
        folder.setFileName(dto.getName());
        // 标记为文件夹
        folder.setIsDir("1");

        // 3. 直接复用之前写的 Mapper 插入逻辑表
        // Mybatis 会自动把数据库生成的数字 ID 回填到 folder.getId() 中
        fileMapper.insertSysUserFile(folder);

        // 4. 拼接成统一的字符串 ID 返回
        // 文档里写的是 dir_xxx，但为了和 GET /files 列表里的 CONCAT('f_', id) 保持统一，
        // 建议全局使用 f_ 前缀，这样前端点击文件夹进入下一级时逻辑最简单。
        return "f_" + folder.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(FileRenameDTO dto, Long userId) {
        // 1. 获取当前操作用户信息
        SysUser currentUser = userMapper.selectById(userId);
        if (currentUser == null) throw new RuntimeException("操作用户不存在");

        // 2. 判定权限范围
        // 如果是 ADMIN，则 SQL 中不传 userId（即不限制归属）
        // 如果是 STUDENT 或 MENTOR，重命名仍需限制为本人
        Long userIdFilter = "ADMIN".equals(currentUser.getRole()) ? null : userId;

        // 3. 解析 ID
        Long numericId = Long.valueOf(dto.getId().substring(2));

        // 4. 执行更新
        int rows = fileMapper.updateFileName(numericId, dto.getNewName(), userIdFilter);

        if (rows == 0) {
            throw new RuntimeException("重命名失败：文件不存在或权限不足");
        }
    }

    /**
     * 下载文件
     */
    @Override
    public void download(String id, Long userId, String role, HttpServletResponse response) {
        try {
            // 1. 解析数字 ID (从 "f_123" 提取 123)
            Long numericId = Long.valueOf(id.substring(2));

            // 2. 查询逻辑文件信息
            SysUserFile userFile = fileMapper.selectSysUserFileById(numericId);
            if (userFile == null || "1".equals(userFile.getIsDir())) {
                throw new RuntimeException("文件不存在或暂不支持下载文件夹");
            }

            // 3. 权限校验：学生只能下载自己的，导师和管理员可以下载任何人的
            if ("STUDENT".equals(role) && !userFile.getUserId().equals(userId)) {
                throw new RuntimeException("权限不足，无法下载该文件");
            }

            // 4. 查询物理文件路径
            SysFile physicalFile = fileMapper.selectSysFileById(userFile.getFileId());
            if (physicalFile == null) {
                throw new RuntimeException("物理文件丢失，请联系管理员");
            }

            // 5. 设置响应头，准备“冲”流
            // 解决中文文件名乱码的关键：URLEncoder
            String encodedFileName = URLEncoder.encode(userFile.getFileName(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
            response.setContentType("application/octet-stream");

            // 6. 从 MinIO 获取输入流并写出
            try (InputStream minioStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(physicalFile.getFilePath())
                            .build()
            )) {
                // 使用 Spring 工具类直接将输入流拷贝到响应输出流
                StreamUtils.copy(minioStream, response.getOutputStream());
                response.flushBuffer();
            }

        } catch (Exception e) {
            log.error("文件下载失败", e);
            // 注意：因为返回值是 void，报错时建议手动改写响应状态码或返回错误信息
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 获取文件下载URL
     */
    @Override
    public FileDownloadUrlVO getDownloadUrl(String id, Long userId, String role) {
        try {
            // 1. 解析逻辑文件 ID：f_123 -> 123
            if (id == null || !id.startsWith("f_")) {
                throw new RuntimeException("文件 ID 格式错误");
            }

            Long numericId = Long.valueOf(id.substring(2));

            // 2. 查询用户逻辑文件
            SysUserFile userFile = fileMapper.selectSysUserFileById(numericId);
            if (userFile == null || "1".equals(userFile.getIsDir())) {
                throw new RuntimeException("文件不存在或暂不支持下载文件夹");
            }

            // 3. 权限校验
            // 这里先沿用你原来的逻辑：STUDENT 只能下载自己的，MENTOR / ADMIN 可以下载任意文件
            if ("STUDENT".equals(role) && !userFile.getUserId().equals(userId)) {
                throw new RuntimeException("权限不足，无法下载该文件");
            }

            // 4. 查询物理文件
            SysFile physicalFile = fileMapper.selectSysFileById(userFile.getFileId());
            if (physicalFile == null) {
                throw new RuntimeException("物理文件丢失，请联系管理员");
            }

            // 5. 设置 MinIO 下载响应参数，解决中文文件名问题
            // 注意：这里不是给 Spring Boot response 设置头，而是让 MinIO 下载时返回这些响应头
            String encodedFileName = URLEncoder
                    .encode(userFile.getFileName(), StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            Map<String, String> reqParams = new HashMap<>();
            reqParams.put("response-content-type", "application/octet-stream");
            reqParams.put(
                    "response-content-disposition",
                    "attachment; filename*=UTF-8''" + encodedFileName
            );

            // 6. 生成 5 分钟有效的预签名 URL
            int expireSeconds = 5 * 60;

            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(physicalFile.getFilePath())
                            .expiry(expireSeconds, TimeUnit.SECONDS)
                            .extraQueryParams(reqParams)
                            .build()
            );

            return new FileDownloadUrlVO(url, userFile.getFileName(), expireSeconds);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成预签名下载链接失败，文件ID: {}", id, e);
            throw new RuntimeException("生成下载链接失败，请稍后重试");
        }
    }

    /**
     * 批量移动文件/文件夹
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void moveFiles(FileMoveDTO dto, Long userId, String role) {
        // 1. 基础校验
        if (dto.getIds() == null || dto.getIds().isEmpty()) {
            throw new RuntimeException("请选择要移动的文件或文件夹");
        }
        if (dto.getTargetParentId() == null || dto.getTargetParentId().trim().isEmpty()) {
            throw new RuntimeException("目标目录不能为空");
        }

        // 2. 权限过滤：如果是 ADMIN，则不限制 userId 的归属
        Long userIdFilter = "ADMIN".equals(role) ? null : userId;

        // 3. ID 转换：从 ["f_1", "dir_2"] 提取出 [1, 2]
        List<Long> numericIds = new ArrayList<>();
        for (String idStr : dto.getIds()) {
            try {
                // 兼容处理 f_ 或 dir_ 前缀
                if (idStr.contains("_")) {
                    numericIds.add(Long.valueOf(idStr.substring(idStr.indexOf("_") + 1)));
                } else {
                    numericIds.add(Long.valueOf(idStr)); // 兜底：万一前端传了纯数字
                }
            } catch (Exception e) {
                log.warn("解析移动ID失败，跳过: {}", idStr);
            }
        }

        if (numericIds.isEmpty()) {
            throw new RuntimeException("没有有效的移动对象");
        }

        // 如果目标 targetParentId 在要移动的 numericIds 列表中，直接抛出异常："不能将文件夹移动到自身及其子目录下"。
        // 只有当目标不是根目录时，才需要进行自身比对
        if (!"root".equals(dto.getTargetParentId())) {
            try {
                String targetStr = dto.getTargetParentId();
                // 安全提取目标文件夹的纯数字 ID
                Long targetNumericId = targetStr.startsWith("f_") ?
                        Long.valueOf(targetStr.substring(2)) :
                        Long.valueOf(targetStr);

                // 如果要移动的列表中，包含了目标目录的 ID，果断拦截！
                if (numericIds.contains(targetNumericId)) {
                    throw new RuntimeException("操作非法：不能将文件夹移动到自身内部！");
                }
            } catch (NumberFormatException e) {
                log.warn("解析目标目录 ID 失败，尝试作为普通字符串处理: {}", dto.getTargetParentId());
            }
        }

        // 4. 执行批量更新
        int rows = fileMapper.batchUpdateParentId(numericIds, dto.getTargetParentId(), userIdFilter);

        if (rows == 0) {
            throw new RuntimeException("移动失败：文件不存在或权限不足");
        }
    }

    /**
     * 批量删除文件/文件夹 (移入回收站)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFiles(List<String> ids, Long userId, String role) {
        if (ids == null || ids.isEmpty()) {
            throw new RuntimeException("请选择要删除的文件");
        }

        Long userIdFilter = "ADMIN".equals(role) ? null : userId;

        // 提取前端传来的初始逻辑 ID
        List<Long> topNumericIds = new ArrayList<>();
        for (String idStr : ids) {
            if (idStr != null && idStr.startsWith("f_")) {
                topNumericIds.add(Long.valueOf(idStr.substring(2)));
            }
        }
        if (topNumericIds.isEmpty()) return;

        // 去数据库查一下这些顶层项目到底是文件还是文件夹
        List<SysUserFile> topLevelItems = fileMapper.selectUserFilesByIds(topNumericIds, userIdFilter);
        if (topLevelItems.isEmpty()) {
            throw new RuntimeException("删除失败：文件不存在或无权操作");
        }

        List<Long> allNumericIds = new ArrayList<>();   // 存放最终要判死刑的所有 ID
        Queue<String> folderQueue = new LinkedList<>(); // 只有文件夹才有资格进队列！

        int fileCount = 0;
        int folderCount = 0;

        // 智能分拣
        for (SysUserFile item : topLevelItems) {
            allNumericIds.add(item.getId());
            if ("1".equals(item.getIsDir())) {
                folderQueue.add("f_" + item.getId()); // 是文件夹，进队列等候
                folderCount++;
            } else {
                fileCount++; // 是文件，直接拉黑
            }
        }

        // 仅对文件夹执行 BFS 递归查询
        while (!folderQueue.isEmpty()) {
            List<String> currentParentIds = new ArrayList<>(folderQueue);
            folderQueue.clear();

            List<SysUserFile> children = fileMapper.selectFilesByParentIds(currentParentIds, userIdFilter, 0);

            if (children != null && !children.isEmpty()) {
                for (SysUserFile child : children) {
                    allNumericIds.add(child.getId());
                    if ("1".equals(child.getIsDir())) {
                        folderQueue.add("f_" + child.getId());
                    }
                }
            }
        }

        // 将所有的项目移入回收站
        int rows = fileMapper.updateFileStatus(allNumericIds, userIdFilter, 1);

        if (rows == 0) {
            throw new RuntimeException("删除失败：更新状态异常");
        }

        // 日志反馈
        int childCount = allNumericIds.size() - topLevelItems.size(); // 计算出被连带删除的子项目数量
        log.info("用户 {} 删除操作完成：普通文件 {} 个，文件夹 {} 个 (连带清理子项 {} 个)",
                userId, fileCount, folderCount, childCount);
    }

    /**
     * 分享文件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareLinkVO shareFiles(FileShareDTO dto, Long userId) {
        // 生成唯一分享码
        String shareCode = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

        // 计算过期时间
        LocalDateTime expireTime = null;
        if (dto.getExpireDays() != null && dto.getExpireDays() > 0) {
            // 直接获取当前时间，然后加上指定的天数
            expireTime = LocalDateTime.now().plusDays(dto.getExpireDays());
        }

        // 批量入库分享记录
        for (String idStr : dto.getIds()) {
            if (idStr != null && idStr.startsWith("f_")) {
                Long numericId = Long.valueOf(idStr.substring(2));
                shareMapper.insertShare(numericId, userId, shareCode, expireTime);
            }
        }

        // 返回链接
        ShareLinkVO vo = new ShareLinkVO();
        vo.setShareCode(shareCode);
        vo.setShareUrl("http://lablinkai.com/s/" + shareCode);

        return vo;
    }

    /**
     * 极速预检 (秒传与断点续传)
     * 判定逻辑如下：
     * 物理文件已存在 -> 秒传成功
     * 没人上传 -> 当前用户成为上传 owner
     * 有人正在上传 -> 当前用户进入等待态，拿到共享进度
     * owner 超时 -> 当前用户可以接管续传
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileVerifyVO verifyFile(FileVerifyDTO dto, Long userId) {
        String identifier = dto.getIdentifier();

        if (identifier == null || identifier.trim().isEmpty()) {
            throw new RuntimeException("文件唯一标识 identifier 不能为空");
        }

        if (dto.getTotalSize() == null || dto.getTotalSize() <= 0) {
            throw new RuntimeException("文件大小不合法");
        }

        if (dto.getTotalChunks() == null || dto.getTotalChunks() <= 0) {
            throw new RuntimeException("分片数量不合法");
        }

        // ================= 容量预检 =================
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (user.getUsedStorage() + dto.getTotalSize() > user.getTotalStorage()) {
            throw new RuntimeException("存储空间不足，请清理后重试");
        }

        // ================= 1. 物理文件已存在：直接秒传 =================
        SysFile physicalFile = fileMapper.selectSysFileByIdentifier(identifier);

        if (physicalFile != null) {
            SysUserFile userFile = new SysUserFile();
            userFile.setUserId(userId);
            userFile.setFileId(physicalFile.getId());
            userFile.setParentId(dto.getParentId() == null ? "root" : dto.getParentId());
            userFile.setFileName(dto.getFilename());
            userFile.setIsDir("0");

            fileMapper.insertSysUserFile(userFile);
            // 秒传也要占用当前用户的个人网盘容量，必须走原子扣减
            storageQuotaSupport.deductOrThrow(userId, physicalFile.getFileSize());

            log.info("用户 {} 触发文件秒传，identifier={}, logicId={}",
                    userId, identifier, userFile.getId());

            return FileVerifyAssembler.build(
                    UPLOAD_STATUS_INSTANT_SUCCESS,
                    false,
                    Collections.emptyList(),
                    dto.getTotalChunks(),
                    null,
                    DEFAULT_POLL_INTERVAL,
                    "极速秒传成功"
            );
        }

        // ================= 2. 文件未存在：校验或初始化上传元信息 =================
        uploadRedisSupport.checkOrInitUploadMeta(dto);

        // ================= 3. 获取当前已上传分片 =================
        List<Integer> uploadedChunks = uploadRedisSupport.getUploadedChunks(identifier);

        // ================= 4. 尝试成为上传 owner =================
        String ownerKey = UPLOAD_OWNER_PREFIX + identifier;
        String currentOwner = redisTemplate.opsForValue().get(ownerKey);

        // 当前没有 owner，说明没人正在上传，当前用户成为 owner
        if (currentOwner == null) {
            String uploadSessionId = UUID.randomUUID().toString().replace("-", "");

            Boolean success = redisTemplate.opsForValue().setIfAbsent(
                    ownerKey,
                    uploadSessionId,
                    UPLOAD_OWNER_TTL_SECONDS,
                    TimeUnit.SECONDS
            );

            if (Boolean.TRUE.equals(success)) {
                log.info("用户 {} 成为上传 owner，identifier={}, session={}",
                        userId, identifier, uploadSessionId);

                String status = uploadedChunks.isEmpty()
                        ? UPLOAD_STATUS_CAN_UPLOAD
                        : UPLOAD_STATUS_CAN_TAKEOVER;

                String message = uploadedChunks.isEmpty()
                        ? "可以开始上传"
                        : "原上传会话已超时，可接管续传";

                return FileVerifyAssembler.build(
                        status,
                        true,
                        uploadedChunks,
                        dto.getTotalChunks(),
                        uploadSessionId,
                        DEFAULT_POLL_INTERVAL,
                        message
                );
            }

            // 极小概率下，刚刚有其他请求抢先成为 owner，重新读取
            currentOwner = redisTemplate.opsForValue().get(ownerKey);
        }

        // ================= 5. 已有其他 owner：当前用户进入等待态 =================
        log.info("文件正在由其他会话上传，用户 {} 进入等待态，identifier={}, owner={}",
                userId, identifier, currentOwner);

        return FileVerifyAssembler.build(
                UPLOAD_STATUS_WAITING_UPLOAD,
                true,
                uploadedChunks,
                dto.getTotalChunks(),
                null,
                DEFAULT_POLL_INTERVAL,
                "检测到相同文件正在上传中，正在复用上传进度"
        );
    }

    /**
     * 分片上传
     *
     * 核心逻辑：
     * 1. 校验 uploadSessionId，只有当前 owner 可以上传；
     * 2. 每个合法分片到达时刷新 owner 租约；
     * 3. 将分片写入 MinIO 临时目录 temp/{identifier}/{chunkNumber}；
     * 4. 记录 Redis 分片进度，用于断点续传、共享进度和废弃分片清理。
     */
    @Override
    public boolean uploadChunk(ChunkUploadDTO dto) {
        String identifier = dto.getIdentifier();
        Integer chunkNumber = dto.getChunkNumber();

        try {
            // ================= 1. 基础参数校验 =================
            if (identifier == null || identifier.trim().isEmpty()) {
                throw new RuntimeException("文件唯一标识 identifier 不能为空");
            }

            if (chunkNumber == null || chunkNumber <= 0) {
                throw new RuntimeException("分片序号不合法");
            }

            if (dto.getTotalChunks() == null || dto.getTotalChunks() <= 0) {
                throw new RuntimeException("分片总数不合法");
            }

            if (dto.getFile() == null || dto.getFile().isEmpty()) {
                throw new RuntimeException("分片文件不能为空");
            }

            // ================= 2. 校验上传 owner =================
            uploadRedisSupport.checkUploadOwner(dto);

            // 先刷新一次租约，避免上传过程中 owner 过期
            uploadRedisSupport.refreshUploadOwnerLease(identifier);

            // ================= 3. 幂等处理：如果该分片已记录，直接返回成功 =================
            if (uploadRedisSupport.chunkAlreadyUploaded(identifier, chunkNumber)) {
                uploadRedisSupport.refreshUploadOwnerLease(identifier);
                log.info("分片已存在，跳过重复上传 -> MD5: {}, 序号: {}/{}",
                        identifier, chunkNumber, dto.getTotalChunks());
                return true;
            }

            // ================= 4. 写入 MinIO 临时分片 =================
            String chunkObjectName = "temp/" + identifier + "/" + chunkNumber;

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(chunkObjectName)
                            .stream(dto.getFile().getInputStream(), dto.getFile().getSize(), -1)
                            .contentType("application/octet-stream")
                            .build()
            );

            // ================= 5. 记录进度并刷新租约 =================
            uploadRedisSupport.recordChunkProgress(dto);
            uploadRedisSupport.refreshUploadOwnerLease(identifier);

            log.info("分片到达 -> MD5: {}, session: {}, 序号: {}/{}",
                    identifier, dto.getUploadSessionId(), chunkNumber, dto.getTotalChunks());

            return true;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("分片 {} 上传失败, identifier={}", chunkNumber, identifier, e);
            throw new RuntimeException("分片 " + chunkNumber + " 上传失败");
        }
    }

    /**
     * 容量预检：仅用于提前给出友好提示。
     * 真正的容量一致性由 StorageQuotaSupport.deductOrThrow 保证。
     */
    private void checkStorageEnough(Long userId, Long fileSize) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (fileSize == null || fileSize <= 0) {
            throw new RuntimeException("文件大小异常");
        }

        if (user.getUsedStorage() + fileSize > user.getTotalStorage()) {
            throw new RuntimeException("存储空间不足，请清理后重试");
        }
    }

    /**
     * 物理文件已存在时，直接为当前用户创建逻辑文件记录，实现并发场景下的秒传复用。
     */
    private String createUserFileFromExistingPhysicalFile(SysFile physicalFile, FileMergeDTO dto, Long userId) {
        if (physicalFile == null) {
            throw new RuntimeException("物理文件不存在，无法创建逻辑文件");
        }

        // 容量校验：复用物理文件也要占用当前用户的个人网盘容量
        checkStorageEnough(userId, physicalFile.getFileSize());

        SysUserFile userFile = new SysUserFile();
        userFile.setUserId(userId);
        userFile.setFileId(physicalFile.getId());
        userFile.setParentId(dto.getParentId() == null ? "root" : dto.getParentId());
        userFile.setFileName(dto.getFilename());
        userFile.setIsDir("0");

        fileMapper.insertSysUserFile(userFile);

        storageQuotaSupport.deductOrThrow(userId, physicalFile.getFileSize());

        log.info("用户 {} 复用已存在物理文件，identifier={}, logicId={}",
                userId, dto.getIdentifier(), userFile.getId());

        return "f_" + userFile.getId();
    }

    /**
     * 计算 MinIO 中指定对象的 MD5。
     * 使用流式读取，避免大文件一次性加载到 JVM 内存。
     */
    private String calculateMinioObjectMd5(String objectName) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        )) {
            return DigestUtils.md5DigestAsHex(inputStream);
        } catch (Exception e) {
            log.error("计算 MinIO 对象 MD5 失败，objectName={}", objectName, e);
            throw new RuntimeException("文件完整性校验失败，请重新上传");
        }
    }

    /**
     * 计算 MinIO 中指定对象的 SHA-256。
     * 使用流式读取，避免大文件一次性加载到 JVM 内存。
     */
    private String calculateMinioObjectSha256(String objectName) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        )) {
            return calculateSha256(inputStream);
        } catch (Exception e) {
            log.error("计算 MinIO 对象 SHA-256 失败，objectName={}", objectName, e);
            throw new RuntimeException("文件完整性校验失败，请重新上传");
        }
    }

    /**
     * 零拷贝合并与双表入库
     *
     * 核心逻辑：
     * 1. 使用 Redisson 文件级合并锁，保证同一个 identifier 只会被一个请求合并；
     * 2. 拿到锁后再次查询 sys_file，若物理文件已存在则直接复用；
     * 3. 物理文件不存在时，校验当前请求是否为 upload owner；
     * 4. 检查分片是否完整；
     * 5. 执行 MinIO compose；
     * 6. 双表入库；
     * 7. 清理临时分片与 Redis 上传状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String mergeChunks(FileMergeDTO dto, Long userId) {
        String identifier = dto.getIdentifier();

        if (identifier == null || identifier.trim().isEmpty()) {
            throw new RuntimeException("文件唯一标识 identifier 不能为空");
        }

        if (dto.getTotalChunks() == null || dto.getTotalChunks() <= 0) {
            throw new RuntimeException("分片总数不合法");
        }

        // 用于存储最终合并后的正式文件对象名称，如果合并过程中发生异常，可以用来补偿删除
        String finalObjectName = null;
        boolean finalObjectCreated = false;

        String lockKey = "lock:file:merge:" + identifier;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked = false;

        try {
            /*
             * 最多等待 10 秒获取合并锁。
             * 不指定 leaseTime，使用 Redisson watchdog 自动续期，避免大文件 compose 时间超过固定锁时长。
             */
            locked = lock.tryLock(10, TimeUnit.SECONDS);

            if (!locked) {
                throw new RuntimeException("当前文件正在合并中，请稍后重试");
            }

            log.info("获取文件合并锁成功，identifier={}, userId={}", identifier, userId);

            // ================= 1. 拿锁后再次检查物理文件是否已存在 =================
            // 这是并发复用的关键：如果 A 已经合并入库，B 不应该再次 compose。
            SysFile existedPhysicalFile = fileMapper.selectSysFileByIdentifier(identifier);
            if (existedPhysicalFile != null) {
                String logicFileId = createUserFileFromExistingPhysicalFile(existedPhysicalFile, dto, userId);

                // 物理文件已存在，临时上传状态已经无意义，做一次幂等清理
                minioFileSupport.deleteTempChunks(identifier, dto.getTotalChunks());
                uploadRedisSupport.cleanChunkRedisState(identifier);

                return logicFileId;
            }

            // ================= 2. 物理文件不存在，必须校验当前用户仍是 upload owner =================
            uploadRedisSupport.checkMergeOwner(dto);

            // ================= 3. 检查分片是否完整 =================
            uploadRedisSupport.checkAllChunksUploaded(identifier, dto.getTotalChunks());

            // ================= 4. 准备组装清单 =================
            List<ComposeSource> sourceObjectList = new ArrayList<>();

            for (int i = 1; i <= dto.getTotalChunks(); i++) {
                String chunkObjectName = "temp/" + identifier + "/" + i;

                sourceObjectList.add(
                        ComposeSource.builder()
                                .bucket(bucketName)
                                .object(chunkObjectName)
                                .build()
                );
            }

            // ================= 5. 生成正式文件路径 =================
            String ext = "";
            if (dto.getFilename() != null && dto.getFilename().contains(".")) {
                ext = dto.getFilename().substring(dto.getFilename().lastIndexOf("."));
            }

            String dateStr = java.time.LocalDate.now().toString().replace("-", "/");
            finalObjectName = dateStr + "/" + identifier + ext;

            // ================= 6. 执行 MinIO 零拷贝合并 =================
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucketName)
                            .object(finalObjectName)
                            .sources(sourceObjectList)
                            .build()
            );
            finalObjectCreated = true;

            // ================= 7. 合并后 MD5 复核 =================
            // 注意：必须在 sys_file 入库之前做，防止污染文件进入物理文件表。
            minioFileSupport.verifyMergedFileSha256(finalObjectName, identifier, dto.getTotalChunks());

            // ================= 8. 获取真实合并后的文件大小 =================
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(finalObjectName)
                            .build()
            );

            long actualFileSize = stat.size();

            // 最终入库前再做一次容量校验
            checkStorageEnough(userId, actualFileSize);

            // ================= 9. 双表入库：物理文件表 =================
            SysFile physicalFile = new SysFile();
            physicalFile.setFileIdentifier(identifier);
            physicalFile.setFileSize(actualFileSize);
            physicalFile.setFilePath(finalObjectName);

            fileMapper.insertSysFile(physicalFile);

            // ================= 10. 双表入库：用户逻辑文件表 =================
            SysUserFile userFile = new SysUserFile();
            userFile.setUserId(userId);
            userFile.setFileId(physicalFile.getId());
            userFile.setParentId(dto.getParentId() == null ? "root" : dto.getParentId());
            userFile.setFileName(dto.getFilename());
            userFile.setIsDir("0");

            fileMapper.insertSysUserFile(userFile);

            // ================= 11. 扣减用户网盘容量 =================
            // 通过用户维度分布式锁 + MySQL 条件更新，保证容量扣减原子性
            storageQuotaSupport.deductOrThrow(userId, actualFileSize);

            // ================= 12. 合并成功后清理 MinIO 临时分片和 Redis 上传状态 =================
            minioFileSupport.deleteTempChunks(identifier, dto.getTotalChunks());
            uploadRedisSupport.cleanChunkRedisState(identifier);

            log.info("用户 {} 成功合并大文件: {}, identifier={}",
                    userId, dto.getFilename(), identifier);

            return "f_" + userFile.getId();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("文件合并被中断，请稍后重试");
        } catch (RuntimeException e) {
            if (finalObjectCreated) {
                minioFileSupport.deleteFinalObjectQuietly(finalObjectName);
            }
            throw e;
        } catch (Exception e) {
            if (finalObjectCreated) {
                minioFileSupport.deleteFinalObjectQuietly(finalObjectName);
            }
            log.error("合并文件失败, identifier={}", identifier, e);
            throw new RuntimeException("合并文件失败");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("释放文件合并锁，identifier={}, userId={}", identifier, userId);
            }
        }
    }

}
