package com.nhatnam.server.repository;

import com.nhatnam.server.entity.TaskMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskMessageRepository extends JpaRepository<TaskMessage, Long> {
    Page<TaskMessage> findByRecipientIdOrderByCreatedAtDesc(Long recipientId, Pageable pageable);
    long countByRecipientIdAndIsReadFalse(Long recipientId);
}
