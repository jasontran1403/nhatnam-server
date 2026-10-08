package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Mt5BotClosedOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface Mt5BotClosedOrderRepository extends JpaRepository<Mt5BotClosedOrderEntity, Long> {

    Optional<Mt5BotClosedOrderEntity> findTopByAccountIdOrderByTicketDesc(Long accountId);

    List<Mt5BotClosedOrderEntity> findByAccountIdAndCloseTimeBetweenOrderByCloseTimeDesc(
            Long accountId, LocalDateTime from, LocalDateTime to);

    boolean existsByAccountIdAndTicket(Long accountId, Long ticket);
}
