package com.nhatnam.server.repository;

import com.nhatnam.server.entity.InventoryCostLot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface InventoryCostLotRepository extends JpaRepository<InventoryCostLot, Long> {

    /**
     * Các lô còn hàng (remaining > 0) của 1 nguyên liệu trong 1 kho, sắp theo FIFO.
     * Có khóa ghi (pessimistic write) để trừ kho an toàn khi có nhiều đơn đồng thời.
     *
     * warehouseId == null → chỉ lấy lô kho chung (warehouse IS NULL).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT l FROM InventoryCostLot l
            WHERE l.ingredient.id = :ingredientId
              AND ((:warehouseId IS NULL AND l.warehouse IS NULL)
                   OR l.warehouse.id = :warehouseId)
              AND l.remainingQuantity > 0
            ORDER BY l.createdAt ASC, l.id ASC
            """)
    List<InventoryCostLot> findConsumableForUpdate(
            @Param("ingredientId") Long ingredientId,
            @Param("warehouseId")  Long warehouseId);

    /**
     * Như trên nhưng KHÔNG khóa — dùng để tính lại giá vốn trung bình (đọc thuần).
     */
    @Query("""
            SELECT l FROM InventoryCostLot l
            WHERE l.ingredient.id = :ingredientId
              AND ((:warehouseId IS NULL AND l.warehouse IS NULL)
                   OR l.warehouse.id = :warehouseId)
              AND l.remainingQuantity > 0
            ORDER BY l.createdAt ASC, l.id ASC
            """)
    List<InventoryCostLot> findConsumable(
            @Param("ingredientId") Long ingredientId,
            @Param("warehouseId")  Long warehouseId);

    // ─────────────────────────────────────────────────────────────────────────
    // MỚI: phục vụ màn hình "Danh sách lô" + backfill
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * TẤT CẢ các lô (kể cả lô đã xuất hết) của 1 nguyên liệu trong 1 kho.
     * Sắp xếp lô mới nhất lên đầu để hiển thị.
     */
    @Query("""
            SELECT l FROM InventoryCostLot l
            WHERE l.ingredient.id = :ingredientId
              AND ((:warehouseId IS NULL AND l.warehouse IS NULL)
                   OR l.warehouse.id = :warehouseId)
            ORDER BY l.createdAt DESC, l.id DESC
            """)
    List<InventoryCostLot> findAllForIngredient(
            @Param("ingredientId") Long ingredientId,
            @Param("warehouseId")  Long warehouseId);

    /** Tổng số lượng còn lại của tất cả lô — 0 nếu chưa có lô nào. */
    @Query("""
            SELECT COALESCE(SUM(l.remainingQuantity), 0) FROM InventoryCostLot l
            WHERE l.ingredient.id = :ingredientId
              AND ((:warehouseId IS NULL AND l.warehouse IS NULL)
                   OR l.warehouse.id = :warehouseId)
              AND l.remainingQuantity > 0
            """)
    BigDecimal sumRemaining(
            @Param("ingredientId") Long ingredientId,
            @Param("warehouseId")  Long warehouseId);

    /** Đã tồn tại lô OPENING (backfill) cho cặp ingredient/warehouse này chưa? */
    @Query("""
            SELECT COUNT(l) > 0 FROM InventoryCostLot l
            WHERE l.ingredient.id = :ingredientId
              AND ((:warehouseId IS NULL AND l.warehouse IS NULL)
                   OR l.warehouse.id = :warehouseId)
              AND l.sourceRef = 'OPENING'
            """)
    boolean existsOpeningLot(
            @Param("ingredientId") Long ingredientId,
            @Param("warehouseId")  Long warehouseId);
}