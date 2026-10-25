package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.MatchRecord;
import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.model.enums.MatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MatchRecordRepository extends JpaRepository<MatchRecord, Long> {

    /**
     * 只查当前用户自己的匹配记录。
     */
    Optional<MatchRecord> findByIdAndUser_Id(Long id, Long userId);

    /**
     * 按幂等键查找（对应唯一索引 uk_match_version_job）。
     */
    Optional<MatchRecord> findByResumeVersion_IdAndJob_IdAndEngineAndPromptVersion(
            Long resumeVersionId, Long jobId, MatchEngine engine, String promptVersion);

    /**
     * worker 提交结果、用户重新发起 DEAD 任务时按主键加行锁，避免与其他修改并发。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MatchRecord m where m.id = :id")
    Optional<MatchRecord> findForUpdateById(@Param("id") Long id);

    /**
     * 某个 JD 下的匹配记录第一页，最新的在前。
     */
    List<MatchRecord> findByUser_IdAndJob_IdOrderByIdDesc(Long userId, Long jobId, Pageable pageable);

    /**
     * 游标翻页：WHERE user_id = ? AND job_id = ? AND id < ? ORDER BY id DESC，由 idx_match_user_job 支撑。
     */
    List<MatchRecord> findByUser_IdAndJob_IdAndIdLessThanOrderByIdDesc(Long userId, Long jobId, Long cursor, Pageable pageable);

    /**
     * PROCESSING 超时的恢复候选（worker 崩溃或被强杀）。
     */
    @Query("""
            select m.id from MatchRecord m
            where m.status = :status and m.processingStartedAt < :startedBefore
            order by m.processingStartedAt
            """)
    List<Long> findProcessingTimeoutCandidates(@Param("status") MatchStatus status,
                                               @Param("startedBefore") Instant startedBefore,
                                               Pageable pageable);
}
