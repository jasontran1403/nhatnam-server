package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.InventoryAction;
import jakarta.persistence.*;
import lombok.*;

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
    private Order order; // NULL nếu không phải bán hàng

    /** FK → InventoryBatch. NULL cho log bán hàng (createOrder). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private InventoryBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryAction action; // IMPORT | EXPORT | ADJUST

    @Column(name = "receipt_image_url", length = 500)
    private String receiptImageUrl;

    /** Dương = nhập/dư, Âm = xuất/thiếu, Zero = khớp (ADJUST). */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityBefore;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityAfter;

    /**
     * Với IMPORT: "IS-20260404-0000000001 | NCC: xxx"
     * Với EXPORT thủ công: "ES-20260404-0000000001 | NCC: reason"
     * Với ADJUST: "CS-20260404-0000000001"
     * Với bán hàng (createOrder): "ORD-xxx" — batch_id = NULL
     */
    @Column(columnDefinition = "TEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private Long createdAt;
}