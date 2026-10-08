package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "order_item_ingredient")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    // === SNAPSHOT DATA ===

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId; // Reference

    @Column(name = "cost_price", precision = 19, scale = 2)
    private BigDecimal costPrice;  // Giá vốn tại thời điểm bán (snapshot)

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount; // Tổng giá vốn = costPrice * quantityUsed

    @Column(name = "ingredient_name", nullable = false)
    private String ingredientName; // Snapshot

    @Column(name = "ingredient_image_url")
    private String ingredientImageUrl; // Snapshot

    @Column(name = "quantity_used", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityUsed; // Số lượng đã trừ kho

    @Column(nullable = false)
    private String unit; // Snapshot

    /**
     * Chi tiết phân bổ FIFO theo từng lô giá vốn.
     * - Nếu chỉ lấy từ 1 lô → list có 1 phần tử.
     * - Nếu lấy từ nhiều lô (ví dụ 120 = 100 + 20) → nhiều phần tử.
     * costPrice/costAmount ở trên = giá vốn bình quân của các lô này.
     */
    @OneToMany(mappedBy = "orderItemIngredient",
            cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItemIngredientLot> lots = new ArrayList<>();
}