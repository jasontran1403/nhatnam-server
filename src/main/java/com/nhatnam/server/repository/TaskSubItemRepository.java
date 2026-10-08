package com.nhatnam.server.repository;

import com.nhatnam.server.entity.TaskSubItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskSubItemRepository extends JpaRepository<TaskSubItem, Long> {
    List<TaskSubItem> findByTaskIdOrderByOrderIndexAsc(Long taskId);
    void deleteByTaskId(Long taskId);
}