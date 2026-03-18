package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "order_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)  private Long productId;
    @Column(name = "product_name", nullable = false) private String productName;
    @Column(name = "product_image_url")              private String productImageUrl;
    @Column(name = "variant_id")                     private Long variantId;
    @Column(name = "variant_name")                   private String variantName;
    @Column(name = "unit")                           private String unit;

    // ── Giá ──────────────────────────────────────────────────────────
    @Column(name = "base_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Builder.Default
    @Column(name = "price_mode", nullable = false, length = 20)
    private String priceMode = "BASE";

    @Column(name = "tier_id")          private Long tierId;
    @Column(name = "tier_name")        private String tierName;
    @Column(name = "discount_percent") private Integer discountPercent;

    // ── VAT ──────────────────────────────────────────────────────────
    /** Tỷ lệ VAT snapshot (0, 5, 8, 10) */
    @Builder.Default
    @Column(name = "vat_rate", nullable = false)
    private Integer vatRate = 0;

    /** Số tiền VAT = subtotal * vatRate / 100 (phân bổ sau discount) */
    @Builder.Default
    @Column(name = "vat_amount", precision = 15, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    // ── Số lượng & tổng ──────────────────────────────────────────────
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItemIngredient> orderItemIngredients;
}