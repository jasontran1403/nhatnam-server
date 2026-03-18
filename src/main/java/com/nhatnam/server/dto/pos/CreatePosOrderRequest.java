package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.OrderSource;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreatePosOrderRequest {

    private OrderSource orderSource;
    private String note;
    private String paymentMethod;

    // ── Customer snapshot ─────────────────────────────────────────
    private String customerPhone;
    private String customerName;

    // ── Discount ──────────────────────────────────────────────────
    private Long customerDiscountId;
    private Long discountItemProductId;

    @NotNull @Size(min = 1)
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {

        @NotNull
        private Long productId;

        @NotNull @Min(1)
        private Integer quantity;

        private Integer discountPercent;
        private Integer vatPercent;
        private String note;
        private List<VariantSelection> variantSelections;

        @Data
        public static class SelectedIngredient {
            @NotNull private Long ingredientId;
            @NotNull @Min(1) private Integer selectedCount;
            private Boolean isAddonIngredient;
            private BigDecimal addonPriceSnapshot;
            private BigDecimal addonBasePrice;
            private String addonName;
        }

        @Data
        public static class VariantSelection {
            @NotNull private Long variantId;
            private Boolean isAddonGroup;
            @NotNull @Size(min = 1) private List<SelectedIngredient> selectedIngredients;
        }
    }
}