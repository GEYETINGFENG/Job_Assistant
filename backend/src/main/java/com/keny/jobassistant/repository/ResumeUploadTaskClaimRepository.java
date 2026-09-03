package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.enums.ResumeUploadType;
import com.keny.jobassistant.model.task.ResumeUploadTaskClaim;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 使用 PostgreSQL 行锁安全领取上传任务，支持多个应用实例同时运行 worker。 */
@Repository
public class ResumeUploadTaskClaimRepository {

    private static final String CLAIM_NEXT_TASK_SQL = """
            WITH candidate AS (
                SELECT id
                FROM resume_upload_session
                WHERE status = 'PENDING'
                  AND processing_object_key IS NOT NULL
                  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                ORDER BY COALESCE(next_attempt_at, create_time), create_time
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE resume_upload_session AS task
            SET status = 'PROCESSING',
                attempt_count = task.attempt_count + 1,
                processing_started_at = CURRENT_TIMESTAMP,
                claim_token = CAST(:claimToken AS UUID),
                next_attempt_at = NULL,
                update_time = CURRENT_TIMESTAMP
            FROM candidate
            WHERE task.id = candidate.id
            RETURNING task.id, task.claim_token, task.attempt_count, task.user_id, task.upload_type,
                      task.resume_id, task.resume_name, task.original_filename, task.upload_object_key, task.processing_object_key,
                      task.expected_extension, task.expected_content_type, task.expected_size
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ResumeUploadTaskClaimRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 领取一条到期任务；SKIP LOCKED 会跳过已被其他 worker 锁住的记录，不会相互等待。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ResumeUploadTaskClaim> claimNext(UUID claimToken) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("claimToken", claimToken);
        List<ResumeUploadTaskClaim> claims = jdbcTemplate.query(CLAIM_NEXT_TASK_SQL, parameters, (resultSet, rowNumber) ->
                new ResumeUploadTaskClaim(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("claim_token", UUID.class),
                        resultSet.getInt("attempt_count"),
                        resultSet.getObject("user_id", Long.class),
                        ResumeUploadType.valueOf(resultSet.getString("upload_type")),
                        resultSet.getObject("resume_id", Long.class),
                        resultSet.getString("resume_name"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("upload_object_key"),
                        resultSet.getString("processing_object_key"),
                        resultSet.getString("expected_extension"),
                        resultSet.getString("expected_content_type"),
                        resultSet.getLong("expected_size")
                ));
        return claims.stream().findFirst();
    }
}
