package com.keny.jobassistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.model.dto.ResumeCacheStatsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 缓存只保存解析结果，不保存上传任务或用户身份。
 *
 * 缓存三大坑在这里的对应（DESIGN.md 第 9 节）：
 * - 雪崩：写入时 TTL 加 ±jitter 的随机抖动，同一批写入不会在同一时刻集体过期；
 * - 穿透：缓存背后的"数据库"是 LLM。同一份内容连续解析失败达到阈值后，短时间内直接拒绝，不再反复调用模型；
 * - 击穿：同一份内容同时被解析时，用 Redis 互斥锁保证只有一个任务调用模型，其余任务稍后重试并命中缓存。
 */
@Service
@Slf4j
public class ResumeParseCacheService {

    /** 只有锁的持有者才能删除锁：锁过期后被别人重新获取时，旧持有者不能误删。 */
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String modelVersion;
    private final String promptVersion;
    private final Duration ttl;
    private final double ttlJitter;
    private final Duration negativeTtl;
    private final int negativeThreshold;
    private final Duration lockTtl;
    private final RandomGenerator random;

    /** 测试用：只指定基础参数，其余取默认值。 */
    public ResumeParseCacheService(StringRedisTemplate redis, ObjectMapper objectMapper, String modelVersion,
                                   String promptVersion, Duration ttl) {
        this(redis, objectMapper, modelVersion, promptVersion, ttl, 0.1, Duration.ofMinutes(10), 3, Duration.ofMinutes(2),
                RandomGenerator.getDefault());
    }

    @Autowired
    public ResumeParseCacheService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                   @Value("${app.resume.ai.model:qwen3.7-flash-2026-07-15}") String modelVersion,
                                   @Value("${app.resume.ai.prompt-version:v2}") String promptVersion,
                                   @Value("${app.resume.ai.cache-ttl:24h}") Duration ttl,
                                   @Value("${app.resume.ai.cache-ttl-jitter:0.1}") double ttlJitter,
                                   @Value("${app.resume.ai.negative-cache-ttl:10m}") Duration negativeTtl,
                                   @Value("${app.resume.ai.negative-cache-threshold:3}") int negativeThreshold,
                                   @Value("${app.resume.ai.parse-lock-ttl:2m}") Duration lockTtl) {
        this(redis, objectMapper, modelVersion, promptVersion, ttl, ttlJitter, negativeTtl, negativeThreshold, lockTtl,
                RandomGenerator.getDefault());
    }

    ResumeParseCacheService(StringRedisTemplate redis, ObjectMapper objectMapper, String modelVersion, String promptVersion,
                            Duration ttl, double ttlJitter, Duration negativeTtl, int negativeThreshold, Duration lockTtl,
                            RandomGenerator random) {
        if (!modelVersion.matches("[A-Za-z0-9._/-]+") || !promptVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Model and prompt versions must be nonempty key-safe identifiers");
        }
        if (ttl.isNegative() || ttl.isZero() || ttl.toMillis() == 0) {
            throw new IllegalArgumentException("Resume cache TTL must be positive");
        }
        if (ttlJitter < 0 || ttlJitter >= 1) {
            throw new IllegalArgumentException("Resume cache TTL jitter must be in [0, 1)");
        }
        if (negativeTtl.isNegative() || negativeTtl.isZero() || lockTtl.isNegative() || lockTtl.isZero() || negativeThreshold < 1) {
            throw new IllegalArgumentException("Negative cache and lock settings must be positive");
        }
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.ttl = ttl;
        this.ttlJitter = ttlJitter;
        this.negativeTtl = negativeTtl;
        this.negativeThreshold = negativeThreshold;
        this.lockTtl = lockTtl;
        this.random = random;
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
        ResumeParseResult result = read(key);
        String outcome = result == null ? "miss" : "hit";
        record(outcome);
        log.info("简历解析缓存 {}，modelVersion={}, promptVersion={}", outcome, modelVersion, promptVersion);
        return Optional.ofNullable(result);
    }

    /** 拿到锁之后的二次检查：只读结果，不计入命中率（这次请求已经计过一次 miss）。 */
    public Optional<ResumeParseResult> peek(String key) {
        return Optional.ofNullable(read(key));
    }

    private ResumeParseResult read(String key) {
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
        return result;
    }

    /** 缓存写入是尽力而为，不能让已完成的模型调用因 Redis 故障而重试。 */
    public void put(String key, ResumeParseResult result) {
        try {
            if (!isValid(result)) {
                throw new IllegalArgumentException("Incomplete resume result");
            }
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result), jitteredTtl());
            // 解析成功后，之前的失败记录不再有意义。
            redis.delete(failuresKey(key));
        } catch (JsonProcessingException | DataAccessException | IllegalArgumentException exception) {
            log.warn("简历解析缓存写入失败，exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    /** 防雪崩：基础 TTL 上下浮动 ±jitter，同一时间写入的一批缓存不会在同一时刻集体过期。 */
    Duration jitteredTtl() {
        double factor = 1 + (random.nextDouble() * 2 - 1) * ttlJitter;
        return Duration.ofMillis(Math.max(1, Math.round(ttl.toMillis() * factor)));
    }

    /**
     * 防穿透：同一份内容最近是否已经连续解析失败达到阈值。
     * 这类文件（扫描件、不是简历的 PDF）再调用模型也只会再失败，短时间内直接拒绝。
     * Redis 故障时返回 false：宁可多调一次模型，也不误拒正常文件。
     */
    public boolean failedRecently(String key) {
        try {
            String count = redis.opsForValue().get(failuresKey(key));
            return count != null && Long.parseLong(count) >= negativeThreshold;
        } catch (DataAccessException | NumberFormatException exception) {
            log.warn("简历解析失败记录读取失败，exceptionType={}", exception.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 记录一次"模型有响应，但给不出合法简历"的失败。网络错误、限流不算：那是临时问题，和文件本身无关。
     * 计数窗口从第一次失败开始，negativeTtl 后整体过期，相当于短 TTL 的空值缓存。
     */
    public void recordInvalidOutput(String key) {
        try {
            String failuresKey = failuresKey(key);
            Long count = redis.opsForValue().increment(failuresKey);
            if (count != null && count == 1) {
                redis.expire(failuresKey, negativeTtl);
            }
            if (count != null && count >= negativeThreshold) {
                log.warn("同一份简历连续 {} 次解析失败，{} 内不再调用模型", count, negativeTtl);
            }
        } catch (DataAccessException exception) {
            log.warn("简历解析失败记录写入失败，exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 防击穿：同一份内容只允许一个任务调用模型。
     * 锁带过期时间，持有者崩溃也不会永久占住；Redis 故障时不加锁直接放行（最坏多调一次模型）。
     */
    public ParseLock tryLock(String key) {
        String lockKey = lockKey(key);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, lockTtl);
            return Boolean.TRUE.equals(acquired) ? new ParseLock(true, () -> release(lockKey, token)) : new ParseLock(false, () -> { });
        } catch (DataAccessException exception) {
            log.warn("简历解析锁获取失败，不加锁继续，exceptionType={}", exception.getClass().getSimpleName());
            return new ParseLock(true, () -> { });
        }
    }

    private void release(String lockKey, String token) {
        try {
            redis.execute(RELEASE_LOCK, List.of(lockKey), token);
        } catch (DataAccessException exception) {
            // 释放失败也没关系：锁会在 lockTtl 后自动过期。
            log.warn("简历解析锁释放失败，exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    /** 解析锁；acquired 为 false 表示另一个任务正在解析同一份内容。 */
    public record ParseLock(boolean acquired, Runnable releaser) implements AutoCloseable {
        @Override
        public void close() {
            releaser.run();
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

    private static String failuresKey(String key) {
        return key + ":failures";
    }

    private static String lockKey(String key) {
        return key + ":lock";
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
