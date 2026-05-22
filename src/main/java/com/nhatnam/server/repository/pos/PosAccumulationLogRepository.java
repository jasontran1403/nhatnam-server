package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosAccumulationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosAccumulationLogRepository
        extends JpaRepository<PosAccumulationLog, Long> {
    List<PosAccumulationLog> findByCustomerIdAndStoreIdAndMonth(
            Long customerId, Long storeId, String month);
}