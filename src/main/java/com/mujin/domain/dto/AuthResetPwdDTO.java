package com.mujin.domain.dto;
import lombok.Data;

@Data
public class AuthResetPwdDTO {
    private String resetToken;      // 临时凭证
    private String newPassword;     // 最终确认的新密码
}