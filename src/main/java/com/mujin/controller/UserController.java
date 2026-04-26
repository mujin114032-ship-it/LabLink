package com.mujin.controller;

import com.mujin.domain.entity.SysUser;
import com.mujin.domain.vo.Result;
import com.mujin.domain.vo.UserProfileVO;
import com.mujin.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


@Slf4j
@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/{id}")
    public Result<SysUser> getById(@PathVariable Long id) {
        log.info("查询用户: {}", id);
        SysUser user = userService.getById(id);
        log.info("查询用户成功: {}", user);
        return Result.success(user);
    }

    /**
     * 获取用户信息与存储容量
     */
    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile(HttpServletRequest request) {
        // 从拦截器存入的 Request 属性中获取 userId (假设拦截器里 setAttribute 了)
        Long userId = (Long) request.getAttribute("userId");

        UserProfileVO profile = userService.getUserProfile(userId);
        return Result.success(profile);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        // 获取 Header 中的 Token 用于服务端清理
        String token = request.getHeader("Authorization").replace("Bearer ", "");
        userService.logout(token);
        return Result.success("退出成功");
    }


}