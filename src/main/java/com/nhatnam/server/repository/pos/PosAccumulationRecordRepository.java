package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosAccumulationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosAccumulationRecordRepository
        extends JpaRepository<PosAccumulationRecord, Long> {
    Optional<PosAccumulationRecord> findByCustomerIdAndStoreIdAndMonth(
            Long customerId, Long storeId, String month);
    List<PosAccumulationRecord> findByStoreIdAndMonthAndSettledFalse(
            Long storeId, String month);
    List<PosAccumulationRecord> findByCustomerIdAndStoreId(
            Long customerId, Long storeId);
    List<PosAccumulationRecord> findAllByMonthAndSettledFalse(String month);
}



