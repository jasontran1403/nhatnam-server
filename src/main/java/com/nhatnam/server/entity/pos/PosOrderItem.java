package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_order_item")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosOrderItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosOrder order;

    // ── SNAPSHOT sản phẩm ──
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "product_image_url")
    private String productImageUrl;

    // ── SNAPSHOT giá ──
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "default_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal defaultPrice;

    // 0 / 10 / 20 / 100 (%) hoặc -1 nếu app order
    @Column(name = "discount_percent", nullable = false)
    private Integer discountPercent;

    @Column(name = "final_unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalUnitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "category_name", length = 100)
    private String categoryName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    // ── VAT ── (THÊM MỚI)
    @Column(name = "vat_percent", nullable = false)
    @Builder.Default
    private Integer vatPercent = 0;

    @Column(name = "vat_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal vatAmount = BigDecimal.ZERO;

    // ── NOTE ──
    @Column(columnDefinition = "TEXT")
    private String note;

    // ── Nguyên liệu đã chọn (từ variant selections) ──
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    @Builder.Default
    private List<PosOrderItemIngredient> selectedIngredients = new ArrayList<>();

    @Column(name = "addon_amount", precision = 15, scale = 2)
    private BigDecimal addonAmount = BigDecimal.ZERO;

    // NOTE: Các field variant_id / variant_name / variant_min_select / variant_max_select
    // đã được XÓA — thông tin variant giờ nằm trong PosOrderItemIngredient.variantId/variantGroupName
    // (mỗi order item có thể có nhiều variant group)
}