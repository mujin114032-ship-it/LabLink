package com.mujin.mapper;

import com.mujin.domain.entity.SysOperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper {

    @Insert("""
        INSERT INTO sys_operation_log
        (user_id, role, module, operation_type, operation_desc,
         request_method, request_uri, ip, user_agent,
         status, error_msg, cost_time, create_time)
        VALUES
        (#{userId}, #{role}, #{module}, #{operationType}, #{operationDesc},
         #{requestMethod}, #{requestUri}, #{ip}, #{userAgent},
         #{status}, #{errorMsg}, #{costTime}, #{createTime})
    """)
    void insert(SysOperationLog log);
}
