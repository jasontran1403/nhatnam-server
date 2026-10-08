package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Snapshot phân bổ FIFO cho 1 dòng nguyên liệu trong đơn hàng.
 *
 * Khi 1 dòng nguyên liệu được xuất từ nhiều lô giá vốn khác nhau, mỗi lô
 * được lưu thành 1 bản ghi ở đây:
 *   - costLotId : id lô giá vốn gốc (dùng để cộng trả lại đúng lô khi HỦY đơn)
 *   - unitCost  : giá vốn 1 đơn vị của lô tại thời điểm xuất (snapshot)
 *   - quantity  : số lượng lấy từ lô này
 *
 * Ví dụ đơn dùng 120 NLA:
 *   - lot#1 (giá 1.000): quantity = 100
 *   - lot#2 (giá 1.200): quantity =  20
 */
@Entity
@Table(name = "order_item_ingredient_lot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemIngredientLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_ingredient_id", nullable = false)
    private OrderItemIngredient orderItemIngredient;

    /** Id lô giá vốn gốc (không dùng FK cứng để lô có thể bị dọn dẹp mà đơn vẫn giữ snapshot). */
    @Column(name = "cost_lot_id")
    private Long costLotId;

    /** Nhãn nguồn của lô (mã batch / OPENING) để hiển thị. */
    @Column(name = "source_ref", length = 60)
    private String sourceRef;

    @Column(name = "unit_cost", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;
}
