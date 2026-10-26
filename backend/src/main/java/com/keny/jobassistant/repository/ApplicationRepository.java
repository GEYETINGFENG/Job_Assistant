package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.Application;
import com.keny.jobassistant.model.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    /**
     * 防止同一个用户重复申请同一个岗位。
     */
    boolean existsByUser_IdAndJob_Id(Long userId, Long jobId);

    /**
     * 查询当前用户自己的 Application。
     */
    Optional<Application> findByIdAndUser_Id(Long applicationId, Long userId);

    /**
     * 查询当前用户全部申请。
     */
    List<Application> findAllByUser_IdOrderByApplyTimeDesc(Long userId);

    /**
     * 判断某个 ResumeVersion 是否仍然被 Application 引用。
     */
    boolean existsByResumeVersion_Id(Long resumeVersionId);

    /**
     * 条件更新状态：只有当前状态仍然是 fromStatus 时才会修改。
     * 并发的前提条件写进 WHERE，由数据库原子地判断；
     * 返回 0 表示申请不存在、不属于当前用户，或状态已经被其他请求改掉。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Application a
            SET a.status = :toStatus, a.updateTime = :updateTime
            WHERE a.id = :applicationId
              AND a.user.id = :userId
              AND a.status = :fromStatus
            """)
    int transitionStatus(@Param("applicationId") Long applicationId,
                         @Param("userId") Long userId,
                         @Param("fromStatus") ApplicationStatus fromStatus,
                         @Param("toStatus") ApplicationStatus toStatus,
                         @Param("updateTime") LocalDateTime updateTime);

    /**
     * 只校验归属，不加载关联对象。
     */
    boolean existsByIdAndUser_Id(Long applicationId, Long userId);
}
