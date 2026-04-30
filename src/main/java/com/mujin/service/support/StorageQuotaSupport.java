package com.mujin.service.support;

import com.mujin.mapper.FileMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class StorageQuotaSupport {

    private final RedissonClient redissonClient;
    private final FileMapper fileMapper;

    /**
     * 用户维度容量扣减。
     *
     * 作用：
     * 1. Redisson 用户锁：降低同一用户并发扣减冲突；
     * 2. MySQL 条件更新：保证 used_storage + size <= total_storage；
     * 3. 返回 0 行时说明容量不足。
     */
    public void deductOrThrow(Long userId, Long size) {
        if (userId == null) {
            throw new RuntimeException("用户 ID 不能为空");
        }

        if (size == null || size <= 0) {
            throw new RuntimeException("文件大小异常");
        }

        String lockKey = "lock:user:storage:" + userId;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked = false;

        try {
            // 容量扣减是短临界区，等待 3 秒，锁最多持有 10 秒即可
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);

            if (!locked) {
                throw new RuntimeException("当前用户容量正在更新，请稍后重试");
            }

            int rows = fileMapper.tryIncreaseUsedStorage(userId, size);

            if (rows == 0) {
                throw new RuntimeException("存储空间不足，请清理后重试");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("容量扣减被中断，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
