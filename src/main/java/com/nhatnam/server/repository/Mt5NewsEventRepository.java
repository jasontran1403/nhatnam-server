package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Mt5NewsEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface Mt5NewsEventRepository extends JpaRepository<Mt5NewsEventEntity, Long> {

    boolean existsByNameAndEventTime(String name, LocalDateTime eventTime);

    /** Lấy tin có event_time >= from, order asc. Dùng cho upcoming trên UI. */
    List<Mt5NewsEventEntity> findByEventTimeGreaterThanEqualOrderByEventTimeAsc(LocalDateTime from);
}