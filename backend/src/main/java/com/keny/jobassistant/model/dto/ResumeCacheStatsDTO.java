package com.keny.jobassistant.model.dto;

/** 当前模型与提示词版本在 Redis 中保留的累计缓存统计。 */
public record ResumeCacheStatsDTO(String modelVersion, String promptVersion, long hit, long miss, long total, double hitRate) {
}
