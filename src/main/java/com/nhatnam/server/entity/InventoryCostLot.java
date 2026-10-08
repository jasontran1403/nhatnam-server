package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Lô giá vốn theo phương pháp FIFO.
 *
 * Mỗi lần NHẬP KHO tạo ra 1 lô mới với:
 *   - unitCost         : giá vốn 1 đơn vị của lô
 *   - expiryDate       : hạn sử dụng của lô (epoch-millis, nullable)
 *   - originalQuantity : số lượng nhập ban đầu
 *   - remainingQuantity: số lượng còn lại (giảm dần khi xuất/bán, tăng lại khi hủy đơn)
 *
 * Khi XUẤT BÁN / XUẤT KHO: trừ theo thứ tự lô cũ trước (createdAt ASC, id ASC).
 *
 * Một lô "OPENING" (createdAt = 0) đại diện cho tồn kho đã tồn tại trước khi bật
 * cơ chế FIFO. Lô này được sinh bởi InventoryCostLotBackfillRunner với
 * unitCost = 0 và expiryDate = hôm nay + 1 năm.
 *
 * warehouse = null  → kho chung (legacy, dùng ingredient.stockQuantity)
 * warehouse != null → theo từng kho (WarehouseIngredientStock)
 */
@Entity
@Table(
        name = "inventory_cost_lot",
        indexes = {
                @Index(name = "idx_cost_lot_lookup",
                        columnList = "ingredient_id, warehouse_id, remaining_quantity")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCostLot {

    /** Nhãn sourceRef của lô backfill tồn kho cũ. */
    public static final String SOURCE_OPENING = "OPENING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    /** Kho của lô — null = kho chung (legacy). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    /** Giá vốn 1 đơn vị của lô này. */
    @Column(name = "unit_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitCost;

    /** Hạn sử dụng của lô — epoch-millis, nullable. */
    @Column(name = "expiry_date")
    private Long expiryDate;

    /** Số lượng nhập ban đầu của lô. */
    @Column(name = "original_quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal originalQuantity;

    /** Số lượng còn lại chưa xuất của lô (FIFO trừ vào đây). */
    @Column(name = "remaining_quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal remainingQuantity;

    /** FK tới phiếu nhập tạo ra lô (null cho lô OPENING / lô sinh khi hủy đơn). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_batch_id")
    private InventoryBatch sourceBatch;

    /** Nhãn nguồn gốc: mã batch nhập, "OPENING", hoặc "CANCEL-<orderCode>". */
    @Column(name = "source_ref", length = 60)
    private String sourceRef;

    /**
     * Thời điểm tạo lô — dùng để sắp xếp FIFO (nhỏ hơn = cũ hơn = xuất trước).
     * Lô OPENING dùng 0 để luôn được xuất trước tiên.
     */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}