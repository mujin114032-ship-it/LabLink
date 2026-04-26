package com.mujin.controller;

import com.mujin.domain.dto.FileQueryDTO;
import com.mujin.domain.dto.RecycleOperationDTO;
import com.mujin.domain.vo.Result;
import com.mujin.service.RecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/recycle")
public class RecycleController {

    @Autowired
    private RecycleService recycleService;

    /**
     * 获取回收站列表
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> getRecycleList(FileQueryDTO queryDTO, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        queryDTO.setUserId(userId);
        Map<String, Object> result = recycleService.getRecycleList(queryDTO);
        return Result.success(result);
    }

    /**
     * 还原文件
     */
    @PostMapping("/restore")
    public Result<Void> restoreFiles(@RequestBody RecycleOperationDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        recycleService.restoreFiles(dto.getIds(), userId);
        return Result.success("还原成功", null);
    }

    /**
     * 彻底删除
     */
    @PostMapping("/delete")
    public Result<Void> hardDeleteFiles(@RequestBody RecycleOperationDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        recycleService.hardDeleteFiles(dto.getIds(), userId);
        return Result.success("彻底删除成功", null);
    }

    /**
     * 清空回收站
     */
    @PostMapping("/clear")
    public Result<Void> clearRecycleBin(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        recycleService.clearRecycleBin(userId);
        return Result.success("回收站已清空", null);
    }
}