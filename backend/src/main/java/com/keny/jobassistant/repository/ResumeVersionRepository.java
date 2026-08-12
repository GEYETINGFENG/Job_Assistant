package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 简历历史版本查询接口。
 *
 * 新增版本不通过普通 save 方法完成，
 * 而是通过 ResumeVersionAtomicRepository 的原子 SQL 完成。
 */
public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {

    /**
     * 查询当前用户某份未删除 Resume 的指定版本。
     */
    Optional<ResumeVersion> findByResume_IdAndResume_User_IdAndResume_IsDeleteAndVersionNumber(
            Long resumeId, Long userId, Integer isDelete, Integer versionNumber);

    /**
     * 根据 ResumeVersion 主键查询，
     * 同时校验版本属于当前用户并且 Resume 尚未删除。
     * 创建 Application 时使用。
     */
    Optional<ResumeVersion> findByIdAndResume_User_IdAndResume_IsDelete(Long id, Long userId, Integer isDelete);

    /**
     * 查询当前用户某份未删除 Resume 的所有版本。
     */
    List<ResumeVersion> findAllByResume_IdAndResume_User_IdAndResume_IsDeleteOrderByVersionNumberDesc(
            Long resumeId, Long userId, Integer isDelete);
}