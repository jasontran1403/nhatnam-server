package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.InventoryAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "inventory_log")
public class InventoryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order; // NULL nếu là nhập kho thủ công

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryAction action; // IMPORT (nhập), EXPORT (xuất), ADJUST (điều chỉnh)

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity; // Số lượng thay đổi (dương = nhập, âm = xuất)

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityBefore; // Số lượng trước khi thay đổi

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityAfter; // Số lượng sau khi thay đổi

    @Column(columnDefinition = "TEXT")
    private String reason; // "Xuất kho cho đơn hàng #ORD-001", "Nhập kho mới"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // Người thực hiện

    private Long createdAt;
}