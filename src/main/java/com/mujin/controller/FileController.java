package com.mujin.controller;

import com.mujin.domain.dto.*;
import com.mujin.domain.vo.FileDownloadUrlVO;
import com.mujin.domain.vo.FileVerifyVO;
import com.mujin.domain.vo.Result;
import com.mujin.domain.vo.ShareLinkVO;
import com.mujin.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/files")
public class FileController {

    @Autowired
    private FileService fileService;

    /**
     * 获取用户文件列表
     */
    @GetMapping
    public Result<Map<String, Object>> getFileList(FileQueryDTO queryDTO, HttpServletRequest request) {
        // 1. 从拦截器获取当前登录者的 ID 和 角色
        Long currentUserId = (Long) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");

        // 2. 权限判定逻辑
        if (role == null || "STUDENT".equals(role)) {
            queryDTO.setUserId(currentUserId);
        }
        else if ("MENTOR".equals(role) || "ADMIN".equals(role)) {
            if (queryDTO.getUserId() == null) {
                queryDTO.setUserId(currentUserId);
            }
        }

        // 3. 调用 Service
        Map<String, Object> result = fileService.getFileList(queryDTO);
        return Result.success(result);
    }

    /**
     * 上传文件
     */
    @PostMapping("/upload")
    public Result<Map<String, String>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "root") String parentId,
            HttpServletRequest request) {

        // 1. 获取当前用户ID
        Long userId = (Long) request.getAttribute("userId");

        // 2. 调用核心业务逻辑，返回新生成的逻辑网盘文件ID
        String fileId = fileService.upload(file, parentId, userId);

        // 3. 封装返回结果
        Map<String, String> data = new HashMap<>();
        data.put("fileId", fileId);

        return Result.success("上传成功", data);
    }

    /**
     * 新建文件夹
     */
    @PostMapping("/folder")
    public Result<Map<String, String>> createFolder(@RequestBody FolderCreateDTO dto, HttpServletRequest request) {
        // 1. 获取当前用户ID
        Long userId = (Long) request.getAttribute("userId");

        // 2. 调用 Service 创建文件夹
        String folderId = fileService.createFolder(dto, userId);

        // 3. 封装返回结果
        Map<String, String> data = new HashMap<>();
        data.put("id", folderId);

        return Result.success("文件夹创建成功", data);
    }

    /**
     * 重命名文件/文件夹
     */
    @PostMapping("/rename")
    public Result<Void> renameFile(@RequestBody FileRenameDTO dto, HttpServletRequest request) {
        // 1. 获取当前用户 ID，确保用户只能修改自己的文件
        Long userId = (Long) request.getAttribute("userId");

        // 2. 调用 Service 执行修改
        fileService.rename(dto, userId);

        return Result.success("重命名成功", null);
    }

    /**
     * 下载文件
     */
    @GetMapping("/download")
    public void downloadFile(@RequestParam("id") String id, HttpServletRequest request, HttpServletResponse response) {
        // 1. 获取当前用户 ID 和 角色
        Long userId = (Long) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");

        // 2. 调用 Service 执行下载逻辑
        fileService.download(id, userId, role, response);
    }

    /**
     * 获取文件预签名下载链接
     */
    @GetMapping("/download-url")
    public Result<FileDownloadUrlVO> getDownloadUrl(
            @RequestParam("id") String id,
            HttpServletRequest request) {

        // 1. 获取当前用户 ID 和角色
        Long userId = (Long) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");

        // 2. 生成预签名下载链接
        FileDownloadUrlVO vo = fileService.getDownloadUrl(id, userId, role);

        return Result.success("获取下载链接成功", vo);
    }

    /**
     * 批量移动文件/文件夹
     */
    @PostMapping("/move")
    public Result<Void> moveFiles(@RequestBody FileMoveDTO dto, HttpServletRequest request) {
        // 1. 获取当前用户 ID 和 角色
        Long userId = (Long) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");

        // 2. 调用 Service 核心逻辑
        fileService.moveFiles(dto, userId, role);

        return Result.success("移动成功", null);
    }

    /**
     * 批量删除文件/文件夹 (移入回收站)
     */
    @PostMapping("/delete")
    public Result<Void> deleteFiles(@RequestBody RecycleOperationDTO dto, HttpServletRequest request) {
        // 1. 获取当前用户 ID 和 角色
        Long userId = (Long) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");

        // 2. 调用 Service 执行逻辑删除
        fileService.deleteFiles(dto.getIds(), userId, role);

        return Result.success("已成功移入回收站", null);
    }

    /**
     * 分享文件
     */
    @PostMapping("/share")
    public Result<ShareLinkVO> shareFiles(@RequestBody FileShareDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ShareLinkVO vo = fileService.shareFiles(dto, userId);
        return Result.success("分享链接已生成", vo);
    }


    /**
     * 上传单个分片
     * 注意：这里使用的是表单提交 (multipart/form-data)，所以不要加 @RequestBody
     */
    @PostMapping("/chunk")
    public Result<Void> uploadChunk(ChunkUploadDTO dto) {
        fileService.uploadChunk(dto);
        return Result.success("分片上传成功", null);
    }

    /**
     * 合并分片
     */
    @PostMapping("/merge")
    public Result<String> mergeChunks(@RequestBody FileMergeDTO dto, HttpServletRequest request) {
        // 从拦截器获取userId
        Long userId = (Long) request.getAttribute("userId");

        // 调用mergeChunks
        String fileId = fileService.mergeChunks(dto, userId);

        return Result.success("文件合并成功", fileId);
    }

    /**
     * 极速预检 (秒传与断点续传)
     */
    @PostMapping("/verify")
    public Result<FileVerifyVO> verifyFile(@RequestBody FileVerifyDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        FileVerifyVO result = fileService.verifyFile(dto, userId);

        return Result.success(result.getMessage(), result);
    }
//    @PostMapping("/verify")
//    public Result<FileVerifyVO> verifyFile(@RequestBody FileVerifyDTO dto, HttpServletRequest request) {
//        Long userId = (Long) request.getAttribute("userId");
//        FileVerifyVO result = fileService.verifyFile(dto, userId);
//
//        if (!result.getShouldUpload()) {
//            return Result.success("极速秒传成功", result);
//        }
//        return Result.success("需上传分片", result);
//    }

}