package com.mujin.mapper;

import com.mujin.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    /**
     * 根据用户名查询数据库中的记录
     * @param username 用户名
     * @return SysUser 用户实体
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser selectByUsername(String username);

    /**
     * 根据id查询数据库中的记录
     * @param id 用户id
     * @return SysUser 用户实体
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id}")
    SysUser selectById(Long id);

    /**
     * 更新用户密码
     * @param userId 用户id
     * @param newPassword 新密码
     */
    void updatePassword(Long userId, String newPassword);

    /**
     * 插入用户
     * @param user 用户实体
     */
    void insertUser(SysUser user);

    /**
     * 根据手机号查询数据库中的记录
     * @param phone 手机号
     * @return SysUser 用户实体
     */
    @Select("SELECT * FROM sys_user WHERE phone = #{phone}")
    SysUser selectByPhone(String phone);


}
