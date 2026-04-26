package com.mujin.domain.dto;
import lombok.Data;

@Data
public class AuthLoginDTO {
    private String username;
    private String password;
    private String uuid; // 验证码流水号
    private String code; // 验证码答案
}