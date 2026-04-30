package com.mujin.controller;

import com.mujin.domain.dto.RecycleOperationDTO;
import com.mujin.domain.dto.ShareSaveDTO;
import com.mujin.domain.vo.FileDownloadUrlVO;
import com.mujin.domain.vo.PublicShareVO;
import com.mujin.domain.vo.Result;
import com.mujin.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/share")
public class ShareController {

    @Autowired
    private ShareService shareService;

    /**
     * 获取共享文件列表
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> getShareList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword) {

        Map<String, Object> result = shareService.getShareList(page, pageSize, keyword);
        return Result.success(result);
    }

    /**
     * 转存到我的文件
     */
    @PostMapping("/save")
    public Result<Void> saveToMyFiles(@RequestBody ShareSaveDTO dto, HttpServletRequest request) {
        Long currentUserId = (Long) request.getAttribute("userId");
        shareService.saveToMyFiles(dto, currentUserId);
        return Result.success("已成功转存至个人网盘", null);
    }

    /**
     * 下载共享文件
     */
    @GetMapping("/download")
    public void downloadShareFile(@RequestParam("id") String id, HttpServletResponse response) {
        // 调用 Service 执行下载逻辑
        shareService.downloadShareFile(id, response);
    }

    /**
     * 获取公开分享详情，前端有待开发
     */
    @GetMapping("/public/{shareCode}")
    public Result<PublicShareVO> getPublicShare(@PathVariable String shareCode) {
        PublicShareVO vo = shareService.getPublicShare(shareCode);
        return Result.success("获取分享成功", vo);
    }

    /**
     * 获取公开分享文件下载链接
     */
    @GetMapping("/public/download-url")
    public Result<FileDownloadUrlVO> getPublicShareDownloadUrl(
            @RequestParam("shareCode") String shareCode,
            @RequestParam("fileId") String fileId) {

        FileDownloadUrlVO vo = shareService.getPublicShareDownloadUrl(shareCode, fileId);
        return Result.success("获取分享下载链接成功", vo);
    }

    /**
     * 直接下载公开分享文件
     */
    @GetMapping("/public/direct/{shareCode}")
    public void directDownloadShare(
            @PathVariable String shareCode,
            HttpServletResponse response) {
        shareService.directDownloadShare(shareCode, response);
    }

    /**
     * 管理员强制取消分享 (清理共享空间)
     */
    @PostMapping("/delete")
    public Result<Void> deleteShares(@RequestBody RecycleOperationDTO dto, HttpServletRequest request) {
        String role = (String) request.getAttribute("role");

        // 1. 终极权限校验：只有 ADMIN 能清理别人的分享
        if (!"ADMIN".equals(role)) {
            return Result.error("越权操作：仅管理员可清理共享空间");
        }

        // 2. 调用 Service 执行取消分享
        shareService.deleteShares(dto.getIds());

        return Result.success("清理成功", null);
    }


}