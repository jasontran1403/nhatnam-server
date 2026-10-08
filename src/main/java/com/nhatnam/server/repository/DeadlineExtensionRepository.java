package com.nhatnam.server.repository;

import com.nhatnam.server.entity.DeadlineExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeadlineExtensionRepository extends JpaRepository<DeadlineExtension, Long> {
    List<DeadlineExtension> findByTaskIdOrderByCreatedAtDesc(Long taskId);
    Page<DeadlineExtension> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    boolean existsByTaskIdAndStatus(Long taskId, String status);
}
