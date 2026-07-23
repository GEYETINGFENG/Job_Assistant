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
     * 根据简历 ID 和用户 ID 查询简历。
     */
    Optional<Resume> findByIdAndUser_Id(Long id, Long userId);
}