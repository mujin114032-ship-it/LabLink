package com.mujin.controller;

import com.mujin.domain.dto.UserLoginDTO;
import com.mujin.domain.entity.SysUser;
import com.mujin.domain.vo.Result;
import com.mujin.domain.vo.UserProfileVO;
import com.mujin.service.UserService;
import com.mujin.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/user")
public class LoginController{

    @Autowired
    private UserService userService;

    /**
     * 用户登录
     * @param userLoginDTO 登录DTO包含用户名和密码
     * @return Result 登录结果
     */
    @PostMapping("/login")
    public Result login(@RequestBody UserLoginDTO userLoginDTO) {
        try {
            // 调用服务层进行登录验证
            SysUser user = userService.login(userLoginDTO);

            // 生成 JWT 令牌
            String token = JwtUtils.createToken(user.getId(), user.getRole());

            // 构建返回数据
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", user.getUsername());

            return Result.success("登录成功", data);
        } catch (Exception e) {
            log.error("登录失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

}
