package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.TicketFee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketFeeRepository extends JpaRepository<TicketFee, Long> {
    void deleteByTicketId(Long ticketId);
}