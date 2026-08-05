package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
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
     * 根据 Resume ID 查找已经完成的 S3 上传记录。
     */
    Optional<ResumeUploadSession> findByResumeIdAndUser_IdAndStatus(Long resumeId, Long userId, ResumeUploadStatus status);
}