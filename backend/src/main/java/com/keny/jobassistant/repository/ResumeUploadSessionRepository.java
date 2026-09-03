package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeUploadSessionRepository extends JpaRepository<ResumeUploadSession, UUID> {

    /** 查询当前用户自己的任务；用户 ID 放进查询条件可以避免越权查看其他人的上传状态。 */
    Optional<ResumeUploadSession> findByIdAndUser_Id(UUID id, Long userId);

    /**
     * 确认上传时加悲观写锁，防止并发请求重复修改冻结对象 Key 和任务状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ResumeUploadSession session where session.id = :id and session.user.id = :userId")
    Optional<ResumeUploadSession> findForUpdateByIdAndUserId(@Param("id") UUID id, @Param("userId") Long userId);

    /** 后台协调任务恢复状态时按主键加悲观锁，避免与 complete 或 worker 并发修改。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ResumeUploadSession session where session.id = :id")
    Optional<ResumeUploadSession> findForUpdateById(@Param("id") UUID id);

    /** 查询已经生成处理对象 Key、但长时间仍停留在 UPLOADING 的崩溃恢复候选。 */
    @Query("""
            select session
            from ResumeUploadSession session
            where session.status = :status
              and session.processingObjectKey is not null
              and session.updateTime <= :updatedBefore
            order by session.updateTime asc
            """)
    List<ResumeUploadSession> findFreezeRecoveryCandidates(@Param("status") ResumeUploadStatus status,
                                                            @Param("updatedBefore") Instant updatedBefore,
                                                            Pageable pageable);

    /** 查询处理时间过长的 PROCESSING 任务，协调器会把它们重新入队或转为 DEAD。 */
    @Query("""
            select session
            from ResumeUploadSession session
            where session.status = :status
              and session.processingStartedAt <= :startedBefore
            order by session.processingStartedAt asc
            """)
    List<ResumeUploadSession> findProcessingTimeoutCandidates(@Param("status") ResumeUploadStatus status,
                                                               @Param("startedBefore") Instant startedBefore,
                                                               Pageable pageable);

    /**
     * 根据幂等键查询已有的新版本上传操作。
     * userId + resumeId + idempotencyKey
     * 共同确定一次新增版本业务操作。
     */
    @Query("""
            select session
            from ResumeUploadSession session
            where session.user.id = :userId
              and session.resumeId = :resumeId
              and session.uploadType = :uploadType
              and session.idempotencyKey = :idempotencyKey
            """)
    Optional<ResumeUploadSession> findByIdempotencyKey(@Param("userId") Long userId,
                                                       @Param("resumeId") Long resumeId,
                                                       @Param("uploadType") ResumeUploadType uploadType,
                                                       @Param("idempotencyKey") String idempotencyKey);

    /** CREATE 没有目标 resumeId，因此使用用户、上传类型和幂等键查找同一次创建操作。 */
    @Query("""
            select session
            from ResumeUploadSession session
            where session.user.id = :userId
              and session.uploadType = :uploadType
              and session.idempotencyKey = :idempotencyKey
            """)
    Optional<ResumeUploadSession> findCreateByIdempotencyKey(@Param("userId") Long userId,
                                                             @Param("uploadType") ResumeUploadType uploadType,
                                                             @Param("idempotencyKey") String idempotencyKey);

    /**
     * 查询某份简历指定版本对应的已完成 S3 上传记录。
     */
    @Query("""
            select session
            from ResumeUploadSession session
            where session.resumeId = :resumeId
              and session.user.id = :userId
              and session.status = :status
              and session.versionNumber = :versionNumber
            """)
    Optional<ResumeUploadSession> findCompletedVersion(
            @Param("resumeId") Long resumeId,
            @Param("userId") Long userId,
            @Param("status") ResumeUploadStatus status,
            @Param("versionNumber") Integer versionNumber
    );
}
