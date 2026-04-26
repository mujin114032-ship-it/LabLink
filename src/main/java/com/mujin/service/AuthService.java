package com.mujin.service;

import com.mujin.domain.dto.AuthLoginDTO;
import com.mujin.domain.dto.AuthRegisterDTO;
import com.mujin.domain.dto.AuthResetPwdDTO;
import com.mujin.domain.dto.AuthVerifyDTO;

import java.util.Map;

public interface AuthService {
    // 登录方法
    Map<String, Object> login(AuthLoginDTO dto);

    // 注册方法
    void register(AuthRegisterDTO dto);

    // 校验重置密码身份
    String verifyResetIdentity(AuthVerifyDTO dto);

    // 执行密码重置
    void resetPassword(AuthResetPwdDTO dto);
}
