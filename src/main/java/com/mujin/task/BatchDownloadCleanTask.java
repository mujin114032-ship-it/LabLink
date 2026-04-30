package com.mujin.task;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class BatchDownloadCleanTask {

    private final MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${lablink.batch-download.clean-expire-minutes:30}")
    private long cleanExpireMinutes;

    /**
     * 批量下载临时 ZIP 所在目录
     */
    private static final String BATCH_DOWNLOAD_PREFIX = "temp/batch-download/";

    /**
     * 临时 ZIP 保留时间。
     * 预签名 URL 目前是 10 分钟，这里保留 30 分钟，给下载过程留缓冲。
     */
    private static final Duration EXPIRE_DURATION = Duration.ofMinutes(30);

    public BatchDownloadCleanTask(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 每 10 分钟清理一次批量下载临时 ZIP。
     *
     * fixedDelay 含义：
     * 上一次任务执行完成后，等待 10 分钟再执行下一次。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void cleanExpiredBatchDownloadZip() {
        Instant expireBefore = Instant.now().minus(Duration.ofMinutes(cleanExpireMinutes));

        int scannedCount = 0;
        int deletedCount = 0;

        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(BATCH_DOWNLOAD_PREFIX)
                            .recursive(true)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();

                if (item.isDir()) {
                    continue;
                }

                String objectName = item.objectName();

                // 防御性判断：只清理批量下载目录下的 zip 文件
                if (objectName == null
                        || !objectName.startsWith(BATCH_DOWNLOAD_PREFIX)
                        || !objectName.endsWith(".zip")) {
                    continue;
                }

                scannedCount++;

                if (item.lastModified() == null) {
                    log.warn("批量下载临时 ZIP 缺少 lastModified，跳过清理：{}", objectName);
                    continue;
                }

                Instant lastModified = item.lastModified().toInstant();

                if (lastModified.isBefore(expireBefore)) {
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(objectName)
                                    .build()
                    );

                    deletedCount++;
                    log.info("已清理过期批量下载临时 ZIP：{}", objectName);
                }
            }

            if (scannedCount > 0 || deletedCount > 0) {
                log.info("批量下载临时 ZIP 清理完成，扫描 {} 个，删除 {} 个",
                        scannedCount, deletedCount);
            }

        } catch (Exception e) {
            log.error("批量下载临时 ZIP 清理任务执行失败", e);
        }
    }
}
