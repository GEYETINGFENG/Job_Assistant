package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 查询当前用户某份简历的全部版本。
     */
    @Query("""
            select version
            from ResumeVersion version
            where version.resume.id = :resumeId
              and version.resume.user.id = :userId
            order by version.versionNumber desc
            """)
    List<ResumeVersion> findAllByResumeIdAndUserId(
            @Param("resumeId") Long resumeId,
            @Param("userId") Long userId
    );

    /**
     * 查询当前用户某份简历的指定版本。
     */
    @Query("""
            select version
            from ResumeVersion version
            where version.resume.id = :resumeId
              and version.resume.user.id = :userId
              and version.versionNumber = :versionNumber
            """)
    Optional<ResumeVersion> findByResumeIdAndUserIdAndVersionNumber(
            @Param("resumeId") Long resumeId,
            @Param("userId") Long userId,
            @Param("versionNumber") Integer versionNumber
    );
}