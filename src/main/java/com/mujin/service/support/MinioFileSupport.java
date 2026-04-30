package com.mujin.service.support;

import com.mujin.utils.FileHashUtils;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioFileSupport {

    private final MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 删除某个文件 identifier 对应的全部临时分片
     */
    public void deleteTempChunks(String identifier, Integer totalChunks) {
        if (identifier == null || totalChunks == null || totalChunks <= 0) {
            return;
        }

        for (int i = 1; i <= totalChunks; i++) {
            String chunkObjectName = "temp/" + identifier + "/" + i;

            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(chunkObjectName)
                                .build()
                );
            } catch (Exception e) {
                log.warn("删除临时分片失败，objectName={}", chunkObjectName, e);
            }
        }
    }

    /**
     * 删除 MinIO 中的正式文件对象。
     * 用于合并后 MD5 校验失败或异常补偿。
     */
    public void deleteFinalObjectQuietly(String objectName) {
        if (objectName == null || objectName.trim().isEmpty()) {
            return;
        }

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            log.info("已删除异常正式文件对象：{}", objectName);
        } catch (Exception e) {
            log.warn("删除异常正式文件对象失败，objectName={}", objectName, e);
        }
    }

    public String calculateObjectSha256(String objectName) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        )) {
            return FileHashUtils.calculateSha256(inputStream);
        } catch (Exception e) {
            log.error("计算 MinIO 对象 SHA-256 失败，objectName={}", objectName, e);
            throw new RuntimeException("文件完整性校验失败，请重新上传");
        }
    }

    /**
     * 复核合并后的最终文件 SHA-256。
     * 如果服务端计算结果与客户端声明的 identifier 不一致，则说明文件被污染或 identifier 被伪造。
     */
    public void verifyMergedFileSha256(String finalObjectName, String expectedIdentifier, Integer totalChunks) {
        String serverSha256 = calculateObjectSha256(finalObjectName);

        if (!expectedIdentifier.equalsIgnoreCase(serverSha256)) {
            log.warn("文件完整性校验失败，expected={}, actual={}, object={}",
                    expectedIdentifier, serverSha256, finalObjectName);

            deleteFinalObjectQuietly(finalObjectName);
            deleteTempChunks(expectedIdentifier, totalChunks);

            throw new RuntimeException("文件完整性校验失败，请重新上传");
        }

        log.info("文件完整性校验通过，identifier={}, object={}", expectedIdentifier, finalObjectName);
    }
}