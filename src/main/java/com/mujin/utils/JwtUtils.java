package com.mujin.utils; // 注意换成你自己的包名

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 */
public class JwtUtils {

    // 密钥
    private static final String SECRET_KEY = "LabLinkAI#SecretKey$2026!@#";
    // 过期时间：12小时
    private static final long EXPIRE_TIME = 12 * 60 * 60 * 1000;

    /**
     * 生成 Token
     * @param userId 用户的数据库 ID
     * @param role   用户的角色 (ADMIN/MENTOR/STUDENT)
     * @return jwt 字符串
     */
    public static String createToken(Long userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);

        return Jwts.builder()
                .setClaims(claims) // 设置载荷
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE_TIME)) // 设置过期时间
                .signWith(SignatureAlgorithm.HS512, SECRET_KEY) // 签名算法和密钥
                .compact();
    }

    /**
     * 解析 Token
     * @param token 前端传来的 token
     * @return 解析后的载荷数据
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(SECRET_KEY)
                .parseClaimsJws(token)
                .getBody();
    }
}