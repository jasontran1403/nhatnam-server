package com.nhatnam.server.dto.pos;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder
public class PosOrderItemResponse {
    private Long       id;
    private Long       productId;
    private String     productName;
    private String     productImageUrl;
    private BigDecimal basePrice;
    private BigDecimal defaultPrice;
    private Integer    discountPercent;
    private Integer    vatPercent;        // VAT %
    private BigDecimal vatAmount;         // Số tiền VAT = finalUnitPrice × qty × vatPercent/100
    private BigDecimal finalUnitPrice;
    private Integer    quantity;
    private BigDecimal subtotal;          // chưa cộng VAT
    private String     note;
    private BigDecimal addonAmount;

    // ── Ý 2: bán xé lẻ ────────────────────────────────────────────
    /** true = dòng này là hàng xé lẻ (quantity luôn = 1). */
    private Boolean    looseSale;
    /** Số lượng xé lẻ theo đơn vị nhỏ nhất (2 miếng / 0.25 kg). */
    private BigDecimal looseQuantity;
    /** Đơn vị nhỏ nhất khi xé lẻ ("Miếng" / "Kg"). */
    private String     looseUnit;
    /** Đơn vị chính của nguyên liệu ("Túi" / "Kg"). */
    private String     looseMainUnit;
    private Long       looseIngredientId;

    // Nguyên liệu đã chọn — gom theo từng variant group
    private List<VariantSelectionResponse> variantSelections;

    @Data @Builder
    public static class VariantSelectionResponse {
        private Long   variantId;
        private String variantGroupName;
        private List<PosOrderItemIngredientResponse> selectedIngredients;
    }
}