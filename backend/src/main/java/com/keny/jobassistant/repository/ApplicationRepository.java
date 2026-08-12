package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;

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
}