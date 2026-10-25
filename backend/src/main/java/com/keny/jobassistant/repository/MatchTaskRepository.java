package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.model.task.MatchTaskClaim;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 匹配任务的原子写操作：幂等插入和 SKIP LOCKED 领取。
 */
@Repository
public class MatchTaskRepository {

    /**
     * 唯一索引冲突时什么都不做、不返回 id：并发的相同请求只有一个能插入成功。
     */
    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO match_record (user_id, resume_id, resume_version_id, job_id, status, engine,
                                      model_version, prompt_version, attempt_count, next_attempt_at,
                                      create_time, update_time)
            VALUES (:userId, :resumeId, :resumeVersionId, :jobId, 'PENDING', :engine,
                    :modelVersion, :promptVersion, 0, CURRENT_TIMESTAMP, :now, :now)
            ON CONFLICT (resume_version_id, job_id, engine, prompt_version) DO NOTHING
            RETURNING id
            """;

    private static final String CLAIM_NEXT_TASK_SQL = """
            WITH candidate AS (
                SELECT id
                FROM match_record
                WHERE status = 'PENDING'
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE match_record AS task
            SET status = 'PROCESSING',
                attempt_count = task.attempt_count + 1,
                processing_started_at = CURRENT_TIMESTAMP,
                claim_token = CAST(:claimToken AS UUID),
                next_attempt_at = NULL,
                update_time = LOCALTIMESTAMP
            FROM candidate
            WHERE task.id = candidate.id
            RETURNING task.id, task.claim_token, task.attempt_count, task.user_id, task.resume_version_id, task.job_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public MatchTaskRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return 新插入记录的 id；已经存在相同组合时返回 empty
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> insertIfAbsent(Long userId, Long resumeId, Long resumeVersionId, Long jobId,
                                         MatchEngine engine, String modelVersion, String promptVersion) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("resumeId", resumeId)
                .addValue("resumeVersionId", resumeVersionId)
                .addValue("jobId", jobId)
                .addValue("engine", engine.name())
                .addValue("modelVersion", modelVersion)
                .addValue("promptVersion", promptVersion)
                .addValue("now", LocalDateTime.now());
        List<Long> ids = jdbcTemplate.queryForList(INSERT_IF_ABSENT_SQL, parameters, Long.class);
        return ids.stream().findFirst();
    }

    /**
     * 领取一条到期任务；SKIP LOCKED 会跳过已被其他 worker 锁住的记录，多实例之间不会互相等待。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MatchTaskClaim> claimNext(UUID claimToken) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("claimToken", claimToken);
        List<MatchTaskClaim> claims = jdbcTemplate.query(CLAIM_NEXT_TASK_SQL, parameters, (resultSet, rowNumber) ->
                new MatchTaskClaim(
                        resultSet.getLong("id"),
                        resultSet.getObject("claim_token", UUID.class),
                        resultSet.getInt("attempt_count"),
                        resultSet.getLong("user_id"),
                        resultSet.getLong("resume_version_id"),
                        resultSet.getLong("job_id")
                ));
        return claims.stream().findFirst();
    }
}
