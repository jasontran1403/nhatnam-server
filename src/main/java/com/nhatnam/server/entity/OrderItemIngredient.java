package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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

    @Column(name = "ingredient_name", nullable = false)
    private String ingredientName; // Snapshot

    @Column(name = "ingredient_image_url")
    private String ingredientImageUrl; // Snapshot

    @Column(name = "quantity_used", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityUsed; // Số lượng đã trừ kho

    @Column(nullable = false)
    private String unit; // Snapshot
}