package com.mujin.task;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
//@Component
public class RedissonHealthCheck {

    private final RedissonClient redissonClient;

    public RedissonHealthCheck(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @PostConstruct
    public void check() {
        RLock lock = redissonClient.getLock("lock:test:redisson");

        boolean locked = false;
        try {
            locked = lock.tryLock(3, TimeUnit.SECONDS);

            if (locked) {
                log.info("Redisson 连接成功，测试锁获取成功");
            } else {
                log.warn("Redisson 连接成功，但测试锁获取失败");
            }
        } catch (Exception e) {
            log.error("Redisson 连接失败，请检查 Redis 地址、端口或密码", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("Redisson 测试锁已释放");
            }
        }
    }
}
