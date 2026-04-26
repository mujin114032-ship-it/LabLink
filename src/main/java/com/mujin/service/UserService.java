package com.mujin.service;

import com.mujin.domain.dto.UserLoginDTO;
import com.mujin.domain.entity.SysUser;
import com.mujin.domain.vo.UserProfileVO;

public interface UserService {
    /**
     * 用户登录
     * @param userLoginDTO 登录DTO包含用户名和密码
     * @return SysUser 登录成功返回的用户实体
     * @throws Exception 登录失败时抛出异常
     */
    SysUser login(UserLoginDTO userLoginDTO) throws Exception;

    SysUser getById(Long id);

    UserProfileVO getUserProfile(Long userId);

    void logout(String token);
}