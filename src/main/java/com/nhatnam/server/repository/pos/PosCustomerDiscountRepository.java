package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCustomerDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PosCustomerDiscountRepository extends JpaRepository<PosCustomerDiscount, Long> {

    // Lấy tất cả discount của 1 customer (để hiển thị trong sheet)
    @Query("""
        SELECT cd FROM PosCustomerDiscount cd
        JOIN FETCH cd.program p
        LEFT JOIN FETCH cd.selectedOption
        WHERE cd.customer.id = :customerId
          AND p.status = 'ACTIVE'
          AND p.applyFrom <= :now
          AND p.applyTo   >= :now
    """)
    List<PosCustomerDiscount> findActiveByCustomerId(
            @Param("customerId") Long customerId,
            @Param("now") long now);

    Optional<PosCustomerDiscount> findByCustomerIdAndProgramId(
            Long customerId, Long programId);
}