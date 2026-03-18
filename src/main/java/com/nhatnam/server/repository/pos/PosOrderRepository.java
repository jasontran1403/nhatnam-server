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

// Lưu ý: PosOrderRepository cần extend thêm custom methods.
// Tách PosOrderItemRepository và PosOrderItemIngredientRepository riêng nếu cần.
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
    long countByOrderCodeStartingWith(String prefix);

    // Custom save methods — implement via @Repository + EntityManager, hoặc tách ra service
    default PosOrderItem saveItem(PosOrderItem item) {
        throw new UnsupportedOperationException("Inject PosOrderItemRepository separately");
    }
    default void saveIngredients(List<PosOrderItemIngredient> list) {
        throw new UnsupportedOperationException("Inject PosOrderItemIngredientRepository separately");
    }

    @Query("SELECT MAX(o.orderCode) FROM PosOrder o WHERE o.orderCode LIKE :prefix%")
    Optional<String> findMaxOrderCodeByPrefix(@Param("prefix") String prefix);

    @Query("""
        SELECT o FROM PosOrder o
        LEFT JOIN FETCH o.shift s
        LEFT JOIN FETCH o.items i
        WHERE o.store.id = :storeId
          AND o.createdAt BETWEEN :fromMs AND :toMs
          AND o.status = 'COMPLETED'
        ORDER BY s.openTime ASC, o.createdAt ASC
    """)
    List<PosOrder> findByStoreIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            @Param("storeId") Long storeId,
            @Param("fromMs")  Long fromMs,
            @Param("toMs")    Long toMs);

    // ── Export: all stores + time range (superadmin) ──────────────
    @Query("""
        SELECT o FROM PosOrder o
        LEFT JOIN FETCH o.shift s
        LEFT JOIN FETCH o.items i
        WHERE o.createdAt BETWEEN :fromMs AND :toMs
          AND o.status = 'COMPLETED'
        ORDER BY s.openTime ASC, o.createdAt ASC
    """)
    List<PosOrder> findByCreatedAtBetweenOrderByCreatedAtAsc(
            @Param("fromMs") Long fromMs,
            @Param("toMs")   Long toMs);
}
