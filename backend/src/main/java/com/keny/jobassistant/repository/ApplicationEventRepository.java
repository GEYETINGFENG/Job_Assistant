package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.ApplicationEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    /**
     * 事件历史第一页：最新的事件在前。
     */
    List<ApplicationEvent> findByApplication_IdOrderByIdDesc(Long applicationId, Pageable pageable);

    /**
     * 游标翻页：只取比上一页最后一条更早的事件。
     * WHERE application_id = ? AND id < ? ORDER BY id DESC，由 idx_application_event_application_id 支撑。
     */
    List<ApplicationEvent> findByApplication_IdAndIdLessThanOrderByIdDesc(Long applicationId, Long cursor, Pageable pageable);
}
