package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Job Repository。
 */
public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * 根据 Job id + 当前用户 id 查询。
     * 不只使用 findById，
     * 避免当前用户通过猜测 Job id 访问其他用户的 Job。
     */
    Optional<Job> findByIdAndUser_Id(Long id, Long userId);

    /**
     * 查询当前用户拥有的全部 Job
     */
    List<Job> findAllByUser_IdOrderByUpdateTimeDesc(Long userId);

    /**
     * 判断 Job 是否属于当前用户
     */
    boolean existsByIdAndUser_Id(Long id, Long userId);
}