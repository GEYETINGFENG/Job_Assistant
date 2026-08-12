package com.keny.jobassistant.repository;
import com.keny.jobassistant.model.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
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
     * 查询当前用户所有未删除 Resume。
     */
    List<Resume> findAllByUser_IdAndIsDeleteOrderByUpdateTimeDesc(Long userId, Integer isDelete);
}