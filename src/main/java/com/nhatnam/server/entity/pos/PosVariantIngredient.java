package com.nhatnam.server.entity.pos;

import com.nhatnam.server.enumtype.AppPlatform;
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

    // ─── Giá addon RIÊNG của nguyên liệu này TRONG món này ──────────────────
    // Mỗi kênh bán một giá, vì giá trên app thường cao hơn giá tại quán.
    // null = chưa set → rơi về giá tại quán, rồi về PosIngredient.addonPrice
    // Cho phép 0 = tặng kèm miễn phí.
    //
    // Chỉ có ý nghĩa khi variant.isAddonGroup = true.

    /** Giá bán TẠI QUÁN (TAKE_AWAY / DINE_IN). */
    @Column(name = "addon_price_override", precision = 15, scale = 2)
    private BigDecimal addonPriceOverride;

    /** Giá bán trên ShopeeFood. */
    @Column(name = "addon_price_shopee", precision = 15, scale = 2)
    private BigDecimal addonPriceShopee;

    /** Giá bán trên GrabFood. */
    @Column(name = "addon_price_grab", precision = 15, scale = 2)
    private BigDecimal addonPriceGrab;

    /** Giá addon mặc định khai báo ở nguyên liệu. Không bao giờ trả null. */
    public BigDecimal defaultAddonPrice() {
        if (ingredient != null && ingredient.getAddonPrice() != null)
            return ingredient.getAddonPrice();
        return BigDecimal.ZERO;
    }

    /** Giá bán tại quán (đã fallback về giá mặc định của nguyên liệu). */
    public BigDecimal resolveAddonPrice() {
        return addonPriceOverride != null ? addonPriceOverride : defaultAddonPrice();
    }

    /**
     * Giá addon theo KÊNH BÁN.
     *
     * @param platform null = bán tại quán; SHOPEE_FOOD / GRAB_FOOD = bán qua app.
     *
     * Fallback cho đơn app: giá app của kênh đó → giá tại quán → giá mặc định
     * của nguyên liệu. Nhờ vậy addon cũ (chưa khai giá app) vẫn bán đúng thay
     * vì trả về 0.
     */
    public BigDecimal resolveAddonPrice(AppPlatform platform) {
        if (platform == AppPlatform.SHOPEE_FOOD && addonPriceShopee != null)
            return addonPriceShopee;
        if (platform == AppPlatform.GRAB_FOOD && addonPriceGrab != null)
            return addonPriceGrab;
        return resolveAddonPrice();
    }
}
