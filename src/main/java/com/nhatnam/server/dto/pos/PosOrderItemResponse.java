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
    private Integer    discountPercent;
    private Integer    vatPercent;        // VAT %
    private BigDecimal vatAmount;         // Số tiền VAT = finalUnitPrice × qty × vatPercent/100
    private BigDecimal finalUnitPrice;
    private Integer    quantity;
    private BigDecimal subtotal;          // chưa cộng VAT
    private String     note;
    private BigDecimal addonAmount;

    // Nguyên liệu đã chọn — gom theo từng variant group
    private List<VariantSelectionResponse> variantSelections;

    @Data @Builder
    public static class VariantSelectionResponse {
        private Long   variantId;
        private String variantGroupName;
        private List<PosOrderItemIngredientResponse> selectedIngredients;
    }
}
