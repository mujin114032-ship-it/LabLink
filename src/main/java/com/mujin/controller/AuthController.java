package com.mujin.controller;

import com.google.code.kaptcha.Producer;
import com.mujin.domain.dto.AuthLoginDTO;
import com.mujin.domain.dto.AuthRegisterDTO;
import com.mujin.domain.dto.AuthResetPwdDTO;
import com.mujin.domain.dto.AuthVerifyDTO;
import com.mujin.domain.vo.Result;
import com.mujin.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private Producer kaptchaProducer;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AuthService authService;

    /**
     * 获取图形验证码
     */
    @GetMapping("/captcha")
    public Result<Map<String, String>> getCaptcha() {
        String capText = kaptchaProducer.createText();
        BufferedImage image = kaptchaProducer.createImage(capText);
        String uuid = UUID.randomUUID().toString().replace("-", "");

        // 存入 Redis，2 分钟有效
        redisTemplate.opsForValue().set("captcha:" + uuid, capText, 2, TimeUnit.MINUTES);

        FastByteArrayOutputStream os = new FastByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", os);
        } catch (IOException e) {
            return Result.error("验证码生成失败");
        }
        String base64Img = Base64.getEncoder().encodeToString(os.toByteArray());

        Map<String, String> data = new HashMap<>();
        data.put("uuid", uuid);
        data.put("img", "data:image/png;base64," + base64Img);

        return Result.success("验证码生成成功", data);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AuthLoginDTO dto) {
        Map<String, Object> data = authService.login(dto);
        return Result.success("登录成功", data);
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody AuthRegisterDTO dto) {
        authService.register(dto);
        return Result.success("注册成功", null);
    }

    /**
     * 忘记密码：身份校验
     */
    @PostMapping("/verify-reset")
    public Result<Map<String, String>> verifyResetIdentity(@RequestBody AuthVerifyDTO dto) {
        String resetToken = authService.verifyResetIdentity(dto);

        Map<String, String> data = new HashMap<>();
        data.put("resetToken", resetToken);
        return Result.success("身份验证成功", data);
    }

    /**
     * 忘记密码：执行密码重置
     */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody AuthResetPwdDTO dto) {
        authService.resetPassword(dto);
        return Result.success("密码重置成功，请重新登录", null);
    }
}