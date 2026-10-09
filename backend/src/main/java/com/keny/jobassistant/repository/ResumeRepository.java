package com.keny.jobassistant.repository;
import com.keny.jobassistant.model.dto.ResumeSummaryDTO;
import com.keny.jobassistant.model.entity.Resume;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * 简历数据访问接口。
 */
@Repository
public interface ResumeRepository extends JpaRepository<Resume, Long> {

    /**
     * 查询当前用户的一份未删除 Resume。
     * 同时带 userId，避免读取其他用户的 Resume。
     */
    Optional<Resume> findByIdAndUser_IdAndIsDelete(Long id, Long userId, Integer isDelete);

    /**
     * 判断当前用户是否拥有这份有效 Resume。
     */
    boolean existsByIdAndUser_IdAndIsDelete(Long id, Long userId, Integer isDelete);

    /**
     * 分页查询当前用户未删除 Resume 的摘要，按最近更新时间倒序。
     * 只查询列表需要的列，不读取 parsed_json；id 作为同一 update_time 下的稳定次序。
     * 由复合部分索引 idx_resume_user_active_update_time 支撑（V16），按索引顺序读取，无需排序。
     * is_delete = 0 必须写成字面量：JDBC 预编译后 PostgreSQL 可能改用通用计划，
     * 参数化的 is_delete = $2 无法匹配部分索引的 WHERE is_delete = 0 条件。
     */
    @Query("""
            select new com.keny.jobassistant.model.dto.ResumeSummaryDTO(
                r.id, r.resumeName, r.latestVersionNumber, r.status, r.createTime, r.updateTime)
            from Resume r
            where r.user.id = :userId and r.isDelete = 0
            order by r.updateTime desc, r.id desc
            """)
    Slice<ResumeSummaryDTO> findActiveSummariesByUserId(@Param("userId") Long userId, Pageable pageable);
}