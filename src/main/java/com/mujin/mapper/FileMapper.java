package com.mujin.mapper;

import com.mujin.domain.dto.FileQueryDTO;
import com.mujin.domain.entity.SysFile;
import com.mujin.domain.entity.SysUserFile;
import com.mujin.domain.vo.FileVO;
import com.mujin.domain.vo.RecycleVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FileMapper {
    // 根据ID查询物理表
    SysFile selectSysFileById(@Param("id") Long id);

    // 根据md5查询文件信息
    SysFile selectSysFileByIdentifier(@Param("identifier") String identifier);

    // 根据逻辑表ID查询文件信息
    SysUserFile selectSysUserFileById(@Param("id") Long id);

    // 向物理表插入记录（返回主键ID）
    int insertSysFile(SysFile sysFile);

    // 向逻辑网盘表插入记录
    int insertSysUserFile(SysUserFile sysUserFile);

    // 查询逻辑网盘表文件信息
    List<FileVO> selectFileList(FileQueryDTO fileQueryDTO);

    // 用户上传文件后，增加用户空间占用(旧版不使用)
    // void increaseUsedStorage(@Param("userId") Long userId, @Param("size") Long size);

    // 尝试增加用户空间占用
    int tryIncreaseUsedStorage(@Param("userId") Long userId, @Param("size") Long size);

    // 重命名文件
    int updateFileName(@Param("id") Long id, @Param("newName") String newName, @Param("userId") Long userId);

    // 批量移动文件/文件夹
    int batchUpdateParentId(@Param("ids") List<Long> ids, @Param("targetParentId") String targetParentId, @Param("userId") Long userId);

    // 查询回收站文件列表
    List<RecycleVO> selectRecycleList(@Param("userId") Long userId);

    // 恢复文件
    int updateFileStatus(@Param("ids") List<Long> ids, @Param("userId") Long userId, @Param("status") Integer status);

    // 删除文件后，更新用户空间占用
    void decreaseUsedStorage(@Param("userId") Long userId, @Param("size") Long size);

    // 计算引用计数
    int countByFileId(@Param("fileId") Long fileId);

    // 查询要删除的逻辑文件记录
    List<SysUserFile> selectUserFilesByIds(@Param("ids") List<Long> ids, @Param("userId") Long userId);

    // 查询用户回收站里的所有文件 ID
    List<Long> selectAllDeletedIdsByUser(@Param("userId") Long userId);

    // 从逻辑表物理删除文件
    void deleteUserFileById(@Param("id") Long id);

    // 从物理表删除删除的文件
    void deleteSysFileById(@Param("id") Long id);

    // 根据一批父目录 ID，查出它们下面所有的子文件和子文件夹
    List<SysUserFile> selectFilesByParentIds(@Param("parentIds") List<String> parentIds, @Param("userId") Long userId, @Param("isDeleted") Integer isDeleted);

}
