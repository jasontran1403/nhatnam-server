package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosEVoucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosEVoucherRepository
        extends JpaRepository<PosEVoucher, Long> {
    List<PosEVoucher> findByCustomerIdAndStoreIdAndStatus(
            Long customerId, Long storeId, PosEVoucher.EVoucherStatus status);
    Optional<PosEVoucher> findByCode(String code);
    List<PosEVoucher> findByStoreIdAndExpiredAtBefore(Long storeId, Long now);
    List<PosEVoucher> findByExpiredAtBeforeAndStatus(
            Long expiredAt, PosEVoucher.EVoucherStatus status);
}
