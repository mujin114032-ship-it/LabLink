package com.mujin.domain.dto;
import lombok.Data;

@Data
public class AuthRegisterDTO {
    private String username;
    private String password;
    private String name;     // 真实姓名
    private String phone;    // 手机号
    private String uuid;
    private String code;
}