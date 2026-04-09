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

    private BigDecimal appDiscountAmount;   // Số tiền giảm (nếu user nhập mode 1)
    private BigDecimal appFinalAmount;      // Giá cuối (nếu user nhập mode 2)
    private BigDecimal manualDiscountAmount;

    @NotNull
    @Size(min = 1)
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {

        @NotNull
        private Long productId;

        @NotNull
        @Min(1)
        private Integer quantity;

        private Integer discountPercent;
        private Integer vatPercent;
        private String note;
        private List<VariantSelection> variantSelections;

        private BigDecimal finalUnitPrice;   // Giá thực tế sau khi user chỉnh sửa (App mode)

        @Data
        public static class SelectedIngredient {

            @NotNull
            private Long ingredientId;

            /**
             * Số lần user chọn nguyên liệu này.
             * VD: 3 miếng bò → selectedCount = 3.
             */
            @NotNull
            @Min(1)
            private Integer selectedCount;

            // ── Addon fields ──────────────────────────────────────
            private Boolean isAddonIngredient;
            private BigDecimal addonPriceSnapshot;
            private BigDecimal addonBasePrice;
            private String addonName;

            /**
             * Danh sách định lượng riêng biệt cho từng unit (override thủ công).
             *
             * Quy tắc:
             *  - Nếu null / rỗng: server dùng selectedCount × stockDeductPerUnit.
             *  - Nếu có: số phần tử PHẢI = selectedCount.
             *    quantityUsed = sum(unitWeights).
             *
             * Ví dụ 3 miếng bò khác trọng lượng:
             *   selectedCount = 3, unitWeights = [0.31, 0.29, 0.32]
             *   → quantityUsed = 0.92 kg
             *
             * Ví dụ 1 Hotdog không override:
             *   selectedCount = 1, unitWeights = null
             *   → quantityUsed = 1 × stockDeductPerUnit
             */
            private List<BigDecimal> unitWeights;
        }

        @Data
        public static class VariantSelection {

            @NotNull
            private Long variantId;

            private Boolean isAddonGroup;

            @NotNull
            @Size(min = 1)
            private List<SelectedIngredient> selectedIngredients;
        }
    }
}