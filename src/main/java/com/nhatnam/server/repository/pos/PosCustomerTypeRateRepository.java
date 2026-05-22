package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCustomerTypeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PosCustomerTypeRateRepository extends JpaRepository<PosCustomerTypeRate, Long> {
    List<PosCustomerTypeRate> findByStoreId(Long storeId);
    Optional<PosCustomerTypeRate> findByStoreIdAndTypeCode(Long storeId, String typeCode);
}