package com.mujin.mapper;

import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.ShareVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ShareMapper {

    // 共享文件列表
    List<ShareVO> selectShareList(String keyword);

    // 获取原始文件信息
    SysUserFile getOriginFileByShareId(Long shareId);

    // 插入分享记录
    void insertShare(@Param("userFileId") Long userFileId,
                     @Param("userId") Long userId,
                     @Param("shareCode") String shareCode,
                     @Param("expireTime") LocalDateTime expireTime);

    // 删除分享记录
    @Delete("DELETE FROM sys_share WHERE user_file_id = #{userFileId}")
    void deleteShareByFileId(Long userFileId);

    // 批量删除分享记录（管理员权限）
    void batchDeleteShares(@Param("ids") List<Long> ids);

}
