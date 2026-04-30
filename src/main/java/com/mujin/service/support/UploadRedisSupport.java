package com.mujin.service.support;

import com.mujin.domain.dto.ChunkUploadDTO;
import com.mujin.domain.dto.FileMergeDTO;
import com.mujin.domain.dto.FileVerifyDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class UploadRedisSupport {

    public static final String UPLOAD_META_PREFIX = "upload:meta:";
    public static final String UPLOAD_OWNER_PREFIX = "upload:owner:";
    public static final String CHUNK_PROGRESS_PREFIX = "chunk_progress:";
    public static final String CHUNK_META_PREFIX = "chunk_meta:";
    public static final String UPLOAD_ACTIVE_KEY = "upload:active";

    public static final long UPLOAD_OWNER_TTL_SECONDS = 120L;
    public static final long UPLOAD_MERGE_TTL_SECONDS = 10L * 60;

    private final StringRedisTemplate redisTemplate;

    /**
     * 获取当前已上传分片
     * @param identifier
     * @return
     */
    public List<Integer> getUploadedChunks(String identifier) {
        String redisKey = CHUNK_PROGRESS_PREFIX + identifier;
        Set<String> uploadedChunkStrs = redisTemplate.opsForSet().members(redisKey);

        List<Integer> uploadedChunks = new ArrayList<>();

        if (uploadedChunkStrs != null && !uploadedChunkStrs.isEmpty()) {
            for (String chunkStr : uploadedChunkStrs) {
                try {
                    uploadedChunks.add(Integer.valueOf(chunkStr));
                } catch (Exception ignored) {
                }
            }
        }

        Collections.sort(uploadedChunks);
        return uploadedChunks;
    }

    /**
     * 检验上传文件的元信息
     * @param dto
     */
    public void checkOrInitUploadMeta(FileVerifyDTO dto) {
        String identifier = dto.getIdentifier();
        String metaKey = UPLOAD_META_PREFIX + identifier;

        Object existTotalSize = redisTemplate.opsForHash().get(metaKey, "totalSize");
        Object existTotalChunks = redisTemplate.opsForHash().get(metaKey, "totalChunks");
        Object existChunkSize = redisTemplate.opsForHash().get(metaKey, "chunkSize");

        if (existTotalSize == null && existTotalChunks == null) {
            redisTemplate.opsForHash().put(metaKey, "totalSize", String.valueOf(dto.getTotalSize()));
            redisTemplate.opsForHash().put(metaKey, "totalChunks", String.valueOf(dto.getTotalChunks()));

            if (dto.getChunkSize() != null) {
                redisTemplate.opsForHash().put(metaKey, "chunkSize", String.valueOf(dto.getChunkSize()));
            }

            redisTemplate.opsForHash().put(metaKey, "filename", dto.getFilename());
            redisTemplate.opsForHash().put(metaKey, "createTime", String.valueOf(System.currentTimeMillis()));
            redisTemplate.expire(metaKey, 48, TimeUnit.HOURS);
            return;
        }

        if (existTotalSize != null && !String.valueOf(dto.getTotalSize()).equals(String.valueOf(existTotalSize))) {
            throw new RuntimeException("文件大小与已有上传任务不一致，疑似非法上传");
        }

        if (existTotalChunks != null && !String.valueOf(dto.getTotalChunks()).equals(String.valueOf(existTotalChunks))) {
            throw new RuntimeException("分片数量与已有上传任务不一致，疑似非法上传");
        }

        if (dto.getChunkSize() != null && existChunkSize != null
                && !String.valueOf(dto.getChunkSize()).equals(String.valueOf(existChunkSize))) {
            throw new RuntimeException("分片大小与已有上传任务不一致，疑似非法上传");
        }
    }


    public String getCurrentOwner(String identifier) {
        return redisTemplate.opsForValue().get(UPLOAD_OWNER_PREFIX + identifier);
    }

    /**
     * 校验当前分片上传请求是否属于当前 upload owner。
     * 如果 session 已过期或不匹配，说明当前用户不再拥有上传权，需要重新预检。
     */
    public void checkUploadOwner(ChunkUploadDTO dto) {
        String identifier = dto.getIdentifier();
        String uploadSessionId = dto.getUploadSessionId();

        if (uploadSessionId == null || uploadSessionId.trim().isEmpty()) {
            throw new RuntimeException("上传会话缺失，请重新选择文件上传");
        }

        String currentOwner = getCurrentOwner(identifier);

        if (currentOwner == null) {
            throw new RuntimeException("上传会话已过期，请重新预检后续传");
        }

        if (!uploadSessionId.equals(currentOwner)) {
            throw new RuntimeException("当前文件正在由其他会话上传，请等待或重新预检");
        }
    }

    /**
     * 刷新当前上传 owner 的租约时间。
     * 只要 owner 持续上传分片，就不会被其他用户接管。
     */
    public void refreshUploadOwnerLease(String identifier) {
        redisTemplate.expire(
                UPLOAD_OWNER_PREFIX + identifier,
                UPLOAD_OWNER_TTL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * 校验当前 merge 请求是否仍然属于当前 upload owner。
     */
    public void checkMergeOwner(FileMergeDTO dto) {
        String identifier = dto.getIdentifier();
        String uploadSessionId = dto.getUploadSessionId();

        if (uploadSessionId == null || uploadSessionId.trim().isEmpty()) {
            throw new RuntimeException("上传会话缺失，请重新预检后再合并");
        }

        String currentOwner = getCurrentOwner(identifier);

        if (currentOwner == null) {
            throw new RuntimeException("上传会话已过期，请重新预检后续传");
        }

        if (!uploadSessionId.equals(currentOwner)) {
            throw new RuntimeException("当前文件正在由其他会话上传或合并，请等待");
        }

        redisTemplate.expire(
                UPLOAD_OWNER_PREFIX + identifier,
                UPLOAD_MERGE_TTL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * 记录分片上传进度，并刷新活跃任务信息。
     */
    public void recordChunkProgress(ChunkUploadDTO dto) {
        String identifier = dto.getIdentifier();

        String redisKey = CHUNK_PROGRESS_PREFIX + identifier;
        redisTemplate.opsForSet().add(redisKey, dto.getChunkNumber().toString());
        redisTemplate.expire(redisKey, 24, TimeUnit.HOURS);

        redisTemplate.opsForZSet().add(UPLOAD_ACTIVE_KEY, identifier, System.currentTimeMillis());

        String metaKey = CHUNK_META_PREFIX + identifier;
        redisTemplate.opsForHash().put(metaKey, "totalChunks", String.valueOf(dto.getTotalChunks()));
        redisTemplate.opsForHash().put(metaKey, "lastChunkNumber", String.valueOf(dto.getChunkNumber()));
        redisTemplate.opsForHash().put(metaKey, "updateTime", String.valueOf(System.currentTimeMillis()));
        redisTemplate.expire(metaKey, 48, TimeUnit.HOURS);
    }

    /**
     * 判断某个分片是否已经上传过。
     */
    public boolean chunkAlreadyUploaded(String identifier, Integer chunkNumber) {
        if (identifier == null || chunkNumber == null) {
            return false;
        }

        Boolean isMember = redisTemplate.opsForSet()
                .isMember(CHUNK_PROGRESS_PREFIX + identifier, chunkNumber.toString());

        return Boolean.TRUE.equals(isMember);
    }

    /**
     * 合并前检查分片是否已经全部上传完成。
     */
    public void checkAllChunksUploaded(String identifier, Integer totalChunks) {
        if (totalChunks == null || totalChunks <= 0) {
            throw new RuntimeException("分片总数不合法");
        }

        List<Integer> uploadedChunks = getUploadedChunks(identifier);

        if (uploadedChunks.size() < totalChunks) {
            throw new RuntimeException("分片尚未上传完整，当前已上传 " + uploadedChunks.size() + "/" + totalChunks);
        }

        for (int i = 1; i <= totalChunks; i++) {
            if (!uploadedChunks.contains(i)) {
                throw new RuntimeException("分片 " + i + " 缺失，请重新预检后续传");
            }
        }
    }

    /**
     * 清理分片上传相关 Redis 状态。
     * 合并成功、物理文件已存在复用、或者校验失败时都可以调用。
     */
    public void cleanChunkRedisState(String identifier) {
        if (identifier == null) {
            return;
        }

        redisTemplate.delete(CHUNK_PROGRESS_PREFIX + identifier);
        redisTemplate.delete(CHUNK_META_PREFIX + identifier);
        redisTemplate.delete(UPLOAD_OWNER_PREFIX + identifier);
        redisTemplate.delete(UPLOAD_META_PREFIX + identifier);
        redisTemplate.opsForZSet().remove(UPLOAD_ACTIVE_KEY, identifier);
    }
}