package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lưu từng nguyên liệu đã chọn trong 1 order item.
 *
 * Thay đổi:
 *  - Thêm {@code defaultDeductPerUnit}: snapshot định lượng mặc định lúc tạo đơn
 *    (clone từ PosVariantIngredient.stockDeductPerUnit).
 *  - Thêm {@code unitWeights}: danh sách định lượng riêng biệt của từng unit
 *    (chỉ có khi user override, vd: [0.31, 0.29, 0.32]).
 *    Nếu null / rỗng → dùng selectedCount × defaultDeductPerUnit.
 *  - {@code quantityUsed} = sum(unitWeights) nếu unitWeights không rỗng,
 *    ngược lại = selectedCount × defaultDeductPerUnit.
 */
@Entity
@Table(name = "pos_order_item_ingredient")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosOrderItemIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private PosOrderItem orderItem;

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @Column(name = "ingredient_name", nullable = false)
    private String ingredientName;

    @Column(name = "ingredient_image_url")
    private String ingredientImageUrl;

    /**
     * Số lần user chọn NL này.
     * VD: chọn 2 Cheddar → selectedCount = 2.
     * VD: chọn 1 Beefsteak + 2 miếng thêm → selectedCount = 3.
     */
    @Column(name = "selected_count", nullable = false)
    private Integer selectedCount;

    /**
     * Định lượng mặc định mỗi unit (snapshot từ PosVariantIngredient.stockDeductPerUnit).
     * VD: 1 miếng bò = 0.2 kg → defaultDeductPerUnit = 0.2.
     */
    @Column(name = "default_deduct_per_unit", nullable = false, precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal defaultDeductPerUnit = BigDecimal.ONE;

    /**
     * Danh sách định lượng từng unit (override thủ công khi tạo order).
     * Số phần tử = selectedCount.
     * VD: [0.31, 0.29, 0.32] cho 3 miếng bò trọng lượng khác nhau.
     *
     * Nếu null hoặc rỗng → quantityUsed = selectedCount × defaultDeductPerUnit.
     * Nếu có → quantityUsed = sum(unitWeights).
     *
     * Được lưu dưới dạng JSON trong DB (vd: "[0.31,0.29,0.32]").
     */
    @Column(name = "unit_weights", columnDefinition = "TEXT")
    @Convert(converter = BigDecimalListConverter.class)
    private List<BigDecimal> unitWeights;

    @Column(name = "addon_price_snapshot", precision = 10, scale = 2)
    private BigDecimal addonPriceSnapshot;

    /**
     * Tổng lượng trừ kho = sum(unitWeights) nếu có, hoặc selectedCount × defaultDeductPerUnit.
     * Được tính và persist khi tạo order, không tính lại runtime.
     */
    @Column(name = "quantity_used", nullable = false, precision = 10, scale = 4)
    private BigDecimal quantityUsed;

    // ── Snapshot variant group ────────────────────────────────────────────────
    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "variant_group_name")
    private String variantGroupName;

    // ─────────────────────────────────────────────────────────────────────────
    // Utility: tính quantityUsed từ unitWeights hoặc fallback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tính {@code quantityUsed} dựa vào unitWeights (nếu có) hoặc fallback.
     * Gọi sau khi set đủ selectedCount, defaultDeductPerUnit, unitWeights.
     */
    public BigDecimal computeQuantityUsed() {
        if (unitWeights != null && !unitWeights.isEmpty()) {
            return unitWeights.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return defaultDeductPerUnit.multiply(
                BigDecimal.valueOf(selectedCount != null ? selectedCount : 1));
    }
}