package com.mujin.interceptor;

import com.mujin.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class TokenInterceptor implements HandlerInterceptor{
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 如果是OPTIONS请求，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 如果不是登录接口，判断请求头中是否有token，如果没有token，返回401错误
        String token = request.getHeader("Authorization");
        // 如果请求头没有，尝试从 URL 参数获取
        // 为了兼容浏览器原生下载（<a> 标签 / window.open）的情况
        if (token == null || token.isEmpty()) {
            token = request.getParameter("token");
        }
        // 判空拦截
        if (token == null || token.isEmpty()) {
            log.error("请求头和参数中均无token,返回401错误");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 如果有token，判断token是否有效
        try {
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7); // 去掉 "Bearer "
            }

            // 解析 Token 并获取 Claims
            var claims = JwtUtils.parseToken(token);

            // 从 Claims 中提取 userId 并存入 Request
            Long userId = Long.valueOf(claims.get("userId").toString());
            request.setAttribute("userId", userId);
            // 从 Claims 中提取 role 并存入 Request
            String role = claims.get("role").toString();
            request.setAttribute("role", role);

            log.info("Token 验证通过，用户 ID: {}, 权限: {}", userId, role);
        } catch (Exception e) {
            log.error("token无效,返回401错误", e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        // 如果token有效，放行，如果token无效，返回401错误
        log.info("token有效,放行");
        return true;
    }
}
