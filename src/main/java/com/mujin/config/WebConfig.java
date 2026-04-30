package com.mujin.config;

import com.mujin.interceptor.TokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private TokenInterceptor tokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/**",          // 登录、注册、验证码等接口
                        "/share/public/**",  // 公开分享接口，无需登录
                        "/error",
                        "/static/**"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 允许所有接口
                .allowedOriginPatterns("*") // 允许所有来源（测试阶段用 *，生产环境建议写死前端地址）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 必须包含 OPTIONS
                .allowedHeaders("*")
                .allowCredentials(true) // 允许携带 Cookie/Token
                .maxAge(3600); // 预检请求的缓存时间
    }

}
