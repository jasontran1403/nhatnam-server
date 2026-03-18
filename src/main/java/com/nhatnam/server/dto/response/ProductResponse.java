package com.nhatnam.server.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductResponse {

    private Long    id;
    private String  name;
    private String  description;
    private String  unit;

    // Category
    private Long    categoryId;
    private String  categoryName;

    private String  imageUrl;
    private Boolean isActive;

    // ── Giá gốc (tier pricing) ────────────────────────────────────
    private BigDecimal basePrice;

    // ── Backward-compat: giá mặc định cũ (dùng cho màn hình danh sách) ──
    private BigDecimal defaultPrice;
    private String     defaultPriceName;

    // vatRate lưu dạng int (0, 5, 8, 10) để Flutter dễ dùng
    private Integer vatRate;

    private Long createdAt;
    private Long updatedAt;

    // ── Khung giá sỉ theo số lượng ───────────────────────────────
    private List<PriceTierResponse> priceTiers;

    // ── Giá cũ (ProductPrice) — giữ lại cho các endpoint backward-compat ─
    private List<PriceResponse> prices;

    // ── Biến thể ──────────────────────────────────────────────────
    private List<VariantResponse> variants;

    // ─────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceTierResponse {
        private Long       id;
        private String     tierName;
        private BigDecimal minQuantity;
        private BigDecimal maxQuantity;
        private BigDecimal price;
        private Integer    sortOrder;
        private Boolean    isActive;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceResponse {
        private Long       id;
        private String     priceName;
        private BigDecimal price;
        private Boolean    isDefault;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VariantResponse {
        private Long    id;
        private String  variantName;
        private Boolean isDefault;
        private List<IngredientItem> ingredients;

        @Data @Builder @NoArgsConstructor @AllArgsConstructor
        public static class IngredientItem {
            private Long   ingredientId;
            private String ingredientName;
            private String ingredientImageUrl;
            private String unit;
        }
    }
}