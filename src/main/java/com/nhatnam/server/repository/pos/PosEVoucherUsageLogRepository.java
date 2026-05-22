package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosEVoucherUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosEVoucherUsageLogRepository
        extends JpaRepository<PosEVoucherUsageLog, Long> {
    List<PosEVoucherUsageLog> findByOrderId(Long orderId);
    List<PosEVoucherUsageLog> findByCustomerIdOrderByUsedAtDesc(Long customerId);
    List<PosEVoucherUsageLog> findByVoucherId(Long voucherId);
}