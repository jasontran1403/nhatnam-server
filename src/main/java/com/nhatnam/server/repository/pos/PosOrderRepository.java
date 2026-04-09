package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.entity.pos.PosOrderItem;
import com.nhatnam.server.entity.pos.PosOrderItemIngredient;
import com.nhatnam.server.entity.pos.PosShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosOrderRepository extends JpaRepository<PosOrder, Long> {
    @Query("""
        SELECT o.customerPhone, SUM(o.finalAmount)
        FROM PosOrder o
        WHERE o.customerPhone IS NOT NULL
          AND o.status = 'COMPLETED'
          AND o.createdAt BETWEEN :from AND :to
        GROUP BY o.customerPhone
    """)
    List<Object[]> sumSpendByCustomerInRange(
            @Param("from") Long from, @Param("to") Long to);

    List<PosOrder> findByShiftOrderByCreatedAtDesc(PosShift shift);

    @Query("SELECT MAX(o.orderCode) FROM PosOrder o WHERE o.orderCode LIKE :prefix%")
    Optional<String> findMaxOrderCodeByPrefix(@Param("prefix") String prefix);
}
