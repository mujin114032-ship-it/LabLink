package com.mujin.service.impl;

import com.mujin.domain.dto.AuthLoginDTO;
import com.mujin.domain.dto.AuthRegisterDTO;
import com.mujin.domain.dto.AuthResetPwdDTO;
import com.mujin.domain.dto.AuthVerifyDTO;
import com.mujin.domain.entity.SysUser;
import com.mujin.mapper.UserMapper;
import com.mujin.service.AuthService;
import com.mujin.utils.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 校验图形验证码
     * @param uuid 验证码唯一标识
     * @param code 验证码内容
     */
    private void checkCaptcha(String uuid, String code) {
        if (uuid == null || code == null) {
            throw new RuntimeException("验证码参数缺失");
        }

        String redisKey = "captcha:" + uuid;
        String redisCode = redisTemplate.opsForValue().get(redisKey);

        if (redisCode == null || !redisCode.equalsIgnoreCase(code)) {
            throw new RuntimeException("验证码错误或已失效，请点击图片刷新");
        }

        // 验证成功后立刻删掉，防止“重放攻击”（拿着验证码无限次试密码）
        redisTemplate.delete(redisKey);
    }

    /**
     * 登录
     * @param dto 登录DTO
     * @return 登录成功后的数据Map
     */
    @Override
    public Map<String, Object> login(AuthLoginDTO dto) {
        // 校验验证码
        checkCaptcha(dto.getUuid(), dto.getCode());

        // 校验账号密码
        SysUser user = userMapper.selectByUsername(dto.getUsername());
        if (user == null || !user.getPassword().equals(dto.getPassword())) {
            throw new RuntimeException("账号或密码错误");
        }

        // 生成 JWT Token
        String token = JwtUtils.createToken(user.getId(), user.getRole());

        // 封装返回给前端的数据
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("name", user.getName());
        data.put("role", user.getRole());

        log.info("用户 {} 登录成功", user.getUsername());
        return data;
    }

    /**
     * 注册
     * @param dto 注册DTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(AuthRegisterDTO dto) {
        // 校验验证码
        checkCaptcha(dto.getUuid(), dto.getCode());

        // 判断用户名是否已被占用
        SysUser existUser = userMapper.selectByUsername(dto.getUsername());
        if (existUser != null) {
            throw new RuntimeException("用户名已被注册，请换一个");
        }

        // 校验手机号合法性
        // 校验手机号是否已被占用
        SysUser existUserByPhone = userMapper.selectByPhone(dto.getPhone());
        if (existUserByPhone != null) {
            throw new RuntimeException("手机号已被注册，请换一个");
        }
        // 校验手机号是否为11位数字
        if (!dto.getPhone().matches("\\d{11}")) {
            throw new RuntimeException("手机号格式错误，请输入11位数字");
        }

        // 构造新用户实体
        SysUser newUser = new SysUser();
        newUser.setUsername(dto.getUsername());
        newUser.setPassword(dto.getPassword()); // 注：商业项目这里要用 BCrypt 加密，内部系统暂用明文
        newUser.setName(dto.getName());
        newUser.setPhone(dto.getPhone());
        newUser.setRole("STUDENT"); // 默认给学生权限

        // 初始化存储容量：总容量 500GB，已用 0 GB
        long defaultTotalStorage = 500L * 1024 * 1024 * 1024; // 500GB 转为 Byte
        newUser.setTotalStorage(defaultTotalStorage);
        newUser.setUsedStorage(0L);

        // 插入数据库
        userMapper.insertUser(newUser);
        log.info("新用户 {} 注册成功，分配 500GB 初始空间", dto.getUsername());
    }

    /**
     * 校验重置密码的账号和预留手机号
     * @param dto 校验重置密码DTO
     * @return 重置密码凭证
     */
    @Override
    public String verifyResetIdentity(AuthVerifyDTO dto) {
        // 校验图形验证码
        checkCaptcha(dto.getUuid(), dto.getCode());

        // 校验账号和预留手机号是否匹配
        SysUser user = userMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            throw new RuntimeException("该账号不存在");
        }
        if (user.getPhone() == null || !user.getPhone().equals(dto.getPhone())) {
            throw new RuntimeException("预留手机号不匹配，无法验证身份");
        }

        // 签发一个有效期为 5 分钟的 resetToken
        String resetToken = "rt_" + java.util.UUID.randomUUID().toString().replace("-", "");

        // 将 Token 存入 Redis，Value 存对应的 username。有效期 5 分钟！
        redisTemplate.opsForValue().set("reset_pwd_token:" + resetToken, dto.getUsername(), 5, TimeUnit.MINUTES);

        log.info("用户 {} 触发了找回密码，已签发重置凭证", dto.getUsername());
        return resetToken;
    }

    /**
     * 重置密码
     * @param dto 重置密码DTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(AuthResetPwdDTO dto) {
        // 验证 resetToken 是否合法
        String redisKey = "reset_pwd_token:" + dto.getResetToken();
        String username = redisTemplate.opsForValue().get(redisKey);

        if (username == null) {
            throw new RuntimeException("重置页已过期或请求非法，请重新验证身份");
        }

        // 查出真实用户并更新密码
        SysUser user = userMapper.selectByUsername(username);
        if (user != null) {
            userMapper.updatePassword(user.getId(), dto.getNewPassword());
            log.info("用户 {} 成功重置了密码", username);
        }

        // 销毁临时凭证
        redisTemplate.delete(redisKey);
    }

}