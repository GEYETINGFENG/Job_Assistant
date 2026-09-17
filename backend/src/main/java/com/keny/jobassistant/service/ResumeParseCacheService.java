package com.keny.jobassistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.model.dto.ResumeCacheStatsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 缓存只保存解析结果，不保存上传任务或用户身份。 */
@Service
@Slf4j
public class ResumeParseCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String modelVersion;
    private final String promptVersion;
    private final Duration ttl;

    public ResumeParseCacheService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                   @Value("${app.resume.ai.model:qwen3.7-flash-2026-07-15}") String modelVersion,
                                   @Value("${app.resume.ai.prompt-version:v1}") String promptVersion,
                                   @Value("${app.resume.ai.cache-ttl:24h}") Duration ttl) {
        if (!modelVersion.matches("[A-Za-z0-9._/-]+") || !promptVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Model and prompt versions must be nonempty key-safe identifiers");
        }
        if (ttl.isNegative() || ttl.isZero() || ttl.toMillis() == 0) {
            throw new IllegalArgumentException("Resume cache TTL must be positive");
        }
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.ttl = ttl;
    }

    /** 按流计算原始字节摘要，不把整份文件加载到内存，也不依赖文件名。 */
    public String keyFor(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return "jobassistant:resume:parse:" + HexFormat.of().formatHex(digest.digest()) + ":" + modelVersion + ":" + promptVersion;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unable to read resume for cache fingerprint");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 根据缓存 key 去 Redis 查有没有以前解析好的结果。
     */
    public Optional<ResumeParseResult> find(String key) {
        ResumeParseResult result = null;
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                try {
                    result = objectMapper.readValue(json, ResumeParseResult.class);
                    if (!isValid(result)) {
                        throw new IllegalArgumentException("Incomplete cached resume result");
                    }
                } catch (JsonProcessingException | IllegalArgumentException exception) {
                    result = null;
                    redis.delete(key);
                    log.warn("简历解析缓存损坏，已删除，modelVersion={}, promptVersion={}", modelVersion, promptVersion);
                }
            }
        } catch (DataAccessException exception) {
            // 无法确定缓存状态时不计 miss，也不继续调用模型。
            log.error("简历解析缓存读取失败，exceptionType={}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Resume cache is unavailable");
        }
        String outcome = result == null ? "miss" : "hit";
        record(outcome);
        log.info("简历解析缓存 {}，modelVersion={}, promptVersion={}", outcome, modelVersion, promptVersion);
        return Optional.ofNullable(result);
    }

    /** 缓存写入是尽力而为，不能让已完成的模型调用因 Redis 故障而重试。 */
    public void put(String key, ResumeParseResult result) {
        try {
            if (!isValid(result)) {
                throw new IllegalArgumentException("Incomplete resume result");
            }
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result), ttl);
        } catch (JsonProcessingException | DataAccessException | IllegalArgumentException exception) {
            log.warn("简历解析缓存写入失败，exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    public ResumeCacheStatsDTO stats() {
        try {
            // 一条 HMGET 同时读取两个计数，避免分别读取期间统计发生变化。
            List<Object> counts = redis.opsForHash().multiGet(statsKey(), List.of("hit", "miss"));
            long hit = counts.get(0) == null ? 0 : Long.parseLong(counts.get(0).toString());
            long miss = counts.get(1) == null ? 0 : Long.parseLong(counts.get(1).toString());
            long total = Math.addExact(hit, miss);
            return new ResumeCacheStatsDTO(modelVersion, promptVersion, hit, miss, total, total == 0 ? 0 : (double) hit / total);
        } catch (DataAccessException | IllegalArgumentException | ArithmeticException exception) {
            log.error("简历缓存统计查询失败，exceptionType={}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Resume cache statistics are unavailable");
        }
    }

    private void record(String outcome) {
        try {
            redis.opsForHash().increment(statsKey(), outcome, 1);
        } catch (DataAccessException exception) {
            log.warn("简历缓存统计写入失败，outcome={}, exceptionType={}", outcome, exception.getClass().getSimpleName());
        }
    }

    private String statsKey() {
        return "jobassistant:resume:parse:stats:" + modelVersion + ":" + promptVersion;
    }

    private boolean isValid(ResumeParseResult result) {
        return result != null && result.parsedJson() != null && result.parsedJson().isObject()
                && result.parsedJson().path("rawText").isTextual() && !result.parsedJson().path("rawText").asText().isBlank()
                && result.mediaType() != null && result.mediaType().equals(result.parsedJson().path("mediaType").asText())
                && (("application/pdf".equals(result.mediaType()) && ".pdf".equals(result.extension()))
                || ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(result.mediaType())
                && ".docx".equals(result.extension())));
    }
}
