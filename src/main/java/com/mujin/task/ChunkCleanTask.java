package com.mujin.task;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class ChunkCleanTask {

    private final MinioClient minioClient;
    private final StringRedisTemplate redisTemplate;

    @Value("${minio.bucketName}")
    private String bucketName;

    /**
     * 分片超过 24 小时没有更新，就认为是废弃上传任务
     */
    private static final long EXPIRE_MILLIS = 24L * 60 * 60 * 1000;

    public ChunkCleanTask(MinioClient minioClient, StringRedisTemplate redisTemplate) {
        this.minioClient = minioClient;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 每小时清理一次废弃分片
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanExpiredChunks() {
        long expireBefore = System.currentTimeMillis() - EXPIRE_MILLIS;

        Set<String> expiredIdentifiers = redisTemplate.opsForZSet()
                .rangeByScore("upload:active", 0, expireBefore);

        if (expiredIdentifiers == null || expiredIdentifiers.isEmpty()) {
            return;
        }

        for (String identifier : expiredIdentifiers) {
            try {
                String prefix = "temp/" + identifier + "/";
                removeObjectsByPrefix(prefix);

                redisTemplate.delete("chunk_progress:" + identifier);
                redisTemplate.delete("chunk_meta:" + identifier);
                redisTemplate.opsForZSet().remove("upload:active", identifier);

                log.info("已清理废弃分片上传任务，identifier={}", identifier);
            } catch (Exception e) {
                log.error("清理废弃分片失败，identifier={}", identifier, e);
            }
        }
    }

    /**
     * 根据 MinIO 对象前缀批量删除临时分片
     */
    private void removeObjectsByPrefix(String prefix) throws Exception {
        Iterable<io.minio.Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .recursive(true)
                        .build()
        );

        for (io.minio.Result<Item> result : results) {
            Item item = result.get();

            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(item.objectName())
                            .build()
            );

            log.info("删除废弃临时分片：{}", item.objectName());
        }
    }
}