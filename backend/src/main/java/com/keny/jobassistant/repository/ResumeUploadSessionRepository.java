package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ResumeUploadSessionRepository extends JpaRepository<ResumeUploadSession, UUID> {

    /**
     * 确认上传时加悲观写锁，防止两个并发确认请求重复创建 Resume。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from ResumeUploadSession session where session.id = :id and session.user.id = :userId")
    Optional<ResumeUploadSession> findForUpdateByIdAndUserId(@Param("id") UUID id, @Param("userId") Long userId);

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