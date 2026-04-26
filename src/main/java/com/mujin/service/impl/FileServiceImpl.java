package com.mujin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mujin.domain.dto.*;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUser;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.FileVO;
import com.mujin.domain.vo.FileVerifyVO;
import com.mujin.domain.vo.ShareLinkVO;
import com.mujin.mapper.FileMapper;
import com.mujin.mapper.ShareMapper;
import com.mujin.mapper.UserMapper;
import com.mujin.service.FileService;
import io.minio.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDateTime;
import java.util.*;


@Service
@Slf4j
public class FileServiceImpl implements FileService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private ShareMapper shareMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${minio.bucketName}")
    private String bucketName;

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

            // ================= 2. 计算 MD5 (用于秒传) =================
            // 注意：这里读取流计算 MD5，Spring 底层会自动管理临时文件
            String md5 = DigestUtils.md5DigestAsHex(file.getInputStream());

            // ================= 3. 物理文件查重与上传 =================
            SysFile physicalFile = fileMapper.selectSysFileByIdentifier(md5);
            Long physicalFileId;

            if (physicalFile != null) {
                // 触发秒传：数据库里已经有这个物理文件了，直接复用 ID
                physicalFileId = physicalFile.getId();
            } else {
                // 真实上传：存入 MinIO
                String originalName = file.getOriginalFilename();
                // 生成在 MinIO 中的存储路径，例如：2026/04/12/xxxx.pdf
                String objectName = generateObjectName(originalName, md5);

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
                newSysFile.setFileIdentifier(md5);
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

            // ================= 5. 扣减容量 =================
            fileMapper.increaseUsedStorage(userId, fileSize);

            return logicFileId;

        } catch (Exception e) {
            // 捕获异常并抛出，触发 @Transactional 事务回滚
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    // 生成 MinIO 对象路径
    private String generateObjectName(String originalName, String md5) {
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        String dateStr = java.time.LocalDate.now().toString().replace("-", "/");
        return dateStr + "/" + md5 + ext;
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
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileVerifyVO verifyFile(FileVerifyDTO dto, Long userId) {
        FileVerifyVO vo = new FileVerifyVO();

        // ================= 容量预检 =================
        SysUser user = userMapper.selectById(userId);
        if (user.getUsedStorage() + dto.getTotalSize() > user.getTotalStorage()) {
            throw new RuntimeException("存储空间不足，请清理后重试");
        }

        // ================= 尝试触发极速秒传 =================
        // 去物理表查询这个 MD5 是否存在
        SysFile physicalFile = fileMapper.selectSysFileByIdentifier(dto.getIdentifier());

        if (physicalFile != null) {
            // 物理硬盘已存在！直接给当前用户发个“软链接”
            SysUserFile userFile = new SysUserFile();
            userFile.setUserId(userId);
            userFile.setFileId(physicalFile.getId());
            userFile.setParentId(dto.getParentId() == null ? "root" : dto.getParentId());
            userFile.setFileName(dto.getFilename());
            userFile.setIsDir("0");

            fileMapper.insertSysUserFile(userFile); // 插入逻辑表
            fileMapper.increaseUsedStorage(userId, physicalFile.getFileSize()); // 扣减容量

            log.info("用户 {} 触发大文件秒传，文件 MD5: {}", userId, dto.getIdentifier());

            // 反馈前端：秒传成功
            vo.setShouldUpload(false);
            return vo;
        }

        // ================= 断点续传预检 =================
        // 如果没有触发秒传，说明需要上传。去 Redis 里查一下之前有没有传过一半的分片
        vo.setShouldUpload(true);

        String redisKey = "chunk_progress:" + dto.getIdentifier();
        Set<String> uploadedChunkStrs = redisTemplate.opsForSet().members(redisKey);

        List<Integer> uploadedChunks = new ArrayList<>();
        if (uploadedChunkStrs != null && !uploadedChunkStrs.isEmpty()) {
            for (String chunkStr : uploadedChunkStrs) {
                uploadedChunks.add(Integer.valueOf(chunkStr));
            }
        }

        vo.setUploadedChunks(uploadedChunks);
        return vo;
    }


    /**
     * 分片上传
     */
    @Override
    public boolean uploadChunk(ChunkUploadDTO dto) {
        try {
            // 拼接临时分片在 MinIO 中的路径，例如： temp/e10adc3949ba59abbe56e057f20f883e/1
            String chunkObjectName = "temp/" + dto.getIdentifier() + "/" + dto.getChunkNumber();

            // 将分片流打进 MinIO
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(chunkObjectName)
                            .stream(dto.getFile().getInputStream(), dto.getFile().getSize(), -1)
                            .contentType("application/octet-stream")
                            .build()
            );

            // 进度存入 Redis (支撑断点续传的核心)
            String redisKey = "chunk_progress:" + dto.getIdentifier();
            redisTemplate.opsForSet().add(redisKey, dto.getChunkNumber().toString());

            // 设置 24 小时过期，防止产生垃圾数据
            redisTemplate.expire(redisKey, 24, java.util.concurrent.TimeUnit.HOURS);

            log.info("分片到达 -> MD5: {}, 序号: {}/{}", dto.getIdentifier(), dto.getChunkNumber(), dto.getTotalChunks());
            return true;

        } catch (Exception e) {
            log.error("分片 {} 上传失败", dto.getChunkNumber(), e);
            throw new RuntimeException("分片 " + dto.getChunkNumber() + " 上传失败");
        }
    }

    /**
     * 阶段三：零拷贝合并与双表入库
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String mergeChunks(FileMergeDTO dto, Long userId) {
        try {
            // ================= 准备组装清单 =================
            List<ComposeSource> sourceObjectList = new ArrayList<>();
            for (int i = 1; i <= dto.getTotalChunks(); i++) {
                // 拼接出刚才上传的临时分片路径
                String chunkObjectName = "temp/" + dto.getIdentifier() + "/" + i;
                sourceObjectList.add(
                        ComposeSource.builder()
                                .bucket(bucketName)
                                .object(chunkObjectName)
                                .build()
                );
            }

            // ================= 生成正式文件的路径 =================
            String ext = "";
            if (dto.getFilename() != null && dto.getFilename().contains(".")) {
                ext = dto.getFilename().substring(dto.getFilename().lastIndexOf("."));
            }
            // 格式: 2026/04/14/e10adc3949ba59abbe56e057f20f883e.pdf
            String dateStr = java.time.LocalDate.now().toString().replace("-", "/");
            String finalObjectName = dateStr + "/" + dto.getIdentifier() + ext;

            // ================= 执行 MinIO 零拷贝合并 =================
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucketName)
                            .object(finalObjectName)
                            .sources(sourceObjectList)
                            .build()
            );

            // ================= 获取真实合并后的大小 =================
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucketName).object(finalObjectName).build()
            );
            long actualFileSize = stat.size();

            // ================= 双表入库 =================
            // 存入物理表 sys_file
            SysFile physicalFile = new SysFile();
            physicalFile.setFileIdentifier(dto.getIdentifier()); // 存入 MD5 指纹
            physicalFile.setFileSize(actualFileSize);
            physicalFile.setFilePath(finalObjectName);
            fileMapper.insertSysFile(physicalFile);

            // 存入逻辑表 sys_user_file
            SysUserFile userFile = new SysUserFile();
            userFile.setUserId(userId);
            userFile.setFileId(physicalFile.getId()); // 绑定刚刚生成的物理 ID
            userFile.setParentId(dto.getParentId() == null ? "root" : dto.getParentId());
            userFile.setFileName(dto.getFilename());
            userFile.setIsDir("0");
            fileMapper.insertSysUserFile(userFile);

            // ================= 收尾工作 =================
            // 扣减用户网盘容量
            fileMapper.increaseUsedStorage(userId, actualFileSize);

            // 删除 Redis 中的分片进度
            String redisKey = "chunk_progress:" + dto.getIdentifier();
            redisTemplate.delete(redisKey);

            log.info("用户 {} 成功合并大文件: {}", userId, dto.getFilename());

            // 返回逻辑文件的 ID (供前端刷新列表)
            return "f_" + userFile.getId();

        } catch (Exception e) {
            log.error("合并文件失败, identifier: {}", dto.getIdentifier(), e);
            throw new RuntimeException("合并文件失败");
        }
    }

}
