package com.mujin.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /**
     * 业务模块，例如：文件管理、共享空间、用户认证
     */
    String module();

    /**
     * 操作类型，例如：UPLOAD、MERGE、DELETE、SHARE、DOWNLOAD_URL
     */
    String type();

    /**
     * 操作描述
     */
    String desc() default "";
}
