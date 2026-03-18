package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_order_item_ingredient")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosOrderItemIngredient {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosOrderItem orderItem;

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @Column(name = "ingredient_name", nullable = false)
    private String ingredientName;

    @Column(name = "ingredient_image_url")
    private String ingredientImageUrl;

    // Số lần user chọn NL này (VD: chọn 2 Cheddar → selectedCount=2)
    @Column(name = "selected_count", nullable = false)
    private Integer selectedCount;

    // Tổng lượng trừ kho = selectedCount × stockDeductPerUnit
    @Column(name = "quantity_used", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantityUsed;

    // ─── Snapshot variant group ─────────────────────────────────────────────
    // Lưu để biết NL này thuộc nhóm chọn nào, phục vụ hiển thị lại
    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "variant_group_name")
    private String variantGroupName;
}