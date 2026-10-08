package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Tồn kho nguyên liệu theo từng kho (warehouse).
 * PK: (warehouse_id, ingredient_id) — unique.
 *
 * Kho cũ (id=1): seed từ ingredient.stockQuantity hiện tại.
 * Kho mới (id=2): bắt đầu từ 0, seller tự nhập kho.
 */
@Entity
@Table(
        name = "warehouse_ingredient_stock",
        uniqueConstraints = @UniqueConstraint(columnNames = {"warehouse_id", "ingredient_id"})
)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WarehouseIngredientStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @Column(name = "stock_quantity", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal stockQuantity = BigDecimal.ZERO;

    /**
     * Giá vốn trung bình (weighted average) của nguyên liệu TRONG KHO NÀY,
     * trên 1 đơn vị tính, làm tròn đến đồng.
     */
    @Column(name = "cost_price", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal costPrice = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}