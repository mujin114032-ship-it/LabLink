package com.mujin.service.impl;

import com.mujin.domain.dto.UserLoginDTO;
import com.mujin.domain.entity.SysUser;
import com.mujin.domain.vo.UserProfileVO;
import com.mujin.mapper.UserMapper;
import com.mujin.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate; // 用于处理 Token 缓存

    /**
     * 用户登录
     * @param userLoginDTO 登录DTO包含用户名和密码
     * @return SysUser 登录成功返回的DTO对象
     * @throws Exception 登录失败时抛出异常
     */
    @Override
    public SysUser login(UserLoginDTO userLoginDTO) throws Exception {
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();

        // 根据用户名查询用户
        SysUser user = userMapper.selectByUsername(username);

        // 验证用户是否存在
        if (user == null) {
            log.warn("用户不存在: {}", username);
            throw new Exception("用户不存在");
        }

        // 验证密码是否正确（实际项目中应该使用加密）
        if (!user.getPassword().equals(password)) {
            log.warn("密码错误: {}", username);
            throw new Exception("密码错误");
        }

        log.info("用户登录成功: {}", username);
        return user;
    }

    /**
     * 根据id查询用户
     * @param id 用户id
     * @return SysUser 用户实体
     */
    @Override
    public SysUser getById(Long id) {
        log.info("根据id查询用户: {}", id);
        return userMapper.selectById(id);
    }

    /**
     * 获取用户存储信息
     * @param userId 用户id
     * @return UserProfileVO 用户存储信息VO
     */
    @Override
    public UserProfileVO getUserProfile(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 构建符合文档规范的 VO
        return UserProfileVO.builder()
                .username(user.getUsername())
                .storageUsed(user.getUsedStorage())
                .storageTotal(user.getTotalStorage())
                .build();
    }

    /**
     * 用户退出
     * @param token 用户登录凭证
     */
    @Override
    public void logout(String token) {
        // 逻辑：将当前 Token 从 Redis 中标记为失效，或者删除对应的用户 Session
        // 假设你的 Token 存放在 Redis 中的 Key 是 "login:token:" + token
        String key = "login:token:" + token;
        redisTemplate.delete(key);
        log.info("用户退出成功，Token 已从 Redis 移除");
    }

}