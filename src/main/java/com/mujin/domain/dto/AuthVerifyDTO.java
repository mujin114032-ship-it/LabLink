package com.mujin.domain.dto;
import lombok.Data;

@Data
public class AuthVerifyDTO {
    private String username;
    private String phone;
    private String uuid;
    private String code;
}