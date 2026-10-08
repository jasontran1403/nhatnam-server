package com.nhatnam.server.repository;

import com.nhatnam.server.entity.TaskProgressLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskProgressLogRepository extends JpaRepository<TaskProgressLog, Long> {
    List<TaskProgressLog> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
