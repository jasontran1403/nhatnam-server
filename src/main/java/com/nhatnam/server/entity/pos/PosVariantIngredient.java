package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_variant_ingredient",
        uniqueConstraints = @UniqueConstraint(columnNames = {"variant_id", "ingredient_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosVariantIngredient {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private PosVariant variant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private PosIngredient ingredient;

    // ─── Trừ kho ───────────────────────────────────────────────────────────
    // Số lượng NL trừ kho khi user chọn 1 lần NL này
    // VD: chọn 1 Cheddar → trừ kho 1 lẻ → stockDeductPerUnit = 1
    // RENAMED từ quantityPerUnit → stockDeductPerUnit
    @Column(name = "stock_deduct_per_unit", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal stockDeductPerUnit = BigDecimal.ONE;

    // ─── Giới hạn riêng của NL này trong nhóm ──────────────────────────────
    // null = không giới hạn riêng (chỉ dùng maxSelect của variant)
    // VD: Hamburger trong nhóm "Chọn loại burger" (maxSelect=1) → null
    //     Cheddar trong nhóm "Chọn 7NL" (maxSelect=7), nhưng Cheddar tối đa 3 → maxSelectableCount=3
    @Column(name = "max_selectable_count")
    private Integer maxSelectableCount;

    // ─── Sub-group: nhóm phụ để giới hạn chung giữa các NL ────────────────
    // TAG để gom các NL có chung giới hạn tổng trong cùng 1 variant
    // VD: Cheddar/Garlic/Thueringer đều có subGroupTag = "sausage"
    //     → tổng 3 loại này không vượt subGroupMaxSelect
    // null = NL không thuộc subgroup nào, không bị giới hạn nhóm
    @Column(name = "sub_group_tag", length = 50)
    private String subGroupTag;

    // Tổng tối đa cho TẤT CẢ NL có cùng subGroupTag trong variant này
    // Chỉ có ý nghĩa khi subGroupTag != null
    // VD: subGroupMaxSelect = 2 → Cheddar + Garlic + Thueringer ≤ 2
    @Column(name = "sub_group_max_select")
    private Integer subGroupMaxSelect;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;
}