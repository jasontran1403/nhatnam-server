package com.nhatnam.server.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {

    private Long   id;
    private String orderCode;
    private String customerName;
    private String customerPhone;
    private String customerEmail;       // ← thêm (dùng trong SellerController invoice)
    private String shippingAddress;

    private BigDecimal subtotal;        // tổng trước chiết khấu
    private BigDecimal discountAmount;
    private BigDecimal vatAmount;
    private BigDecimal totalAmount;     // sau chiết khấu, chưa VAT
    private BigDecimal finalAmount;     // thanh toán cuối = totalAmount + vatAmount

    private String status;
    private String paymentStatus;
    private String paymentMethod;
    private String notes;
    private Long   createdAt;

    private List<OrderItemResponse> items;

    // ─────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemResponse {
        private Long   id;
        private Long   productId;
        private String productName;
        private String productImageUrl;
        private Long   variantId;
        private String variantName;
        private String unit;

        // ── Giá ──────────────────────────────────────────────────────
        private BigDecimal basePrice;
        private BigDecimal unitPrice;
        private String     priceMode;        // BASE | TIER | DISCOUNT_PERCENT

        /** Backward-compat: label hiển thị (tierName hoặc "Giảm X%") */
        private String     priceName;

        private Long       tierId;
        private String     tierName;
        private Integer    discountPercent;

        /** Backward-compat: dùng trong invoice PDF */
        private BigDecimal defaultPrice;

        // ── VAT ──────────────────────────────────────────────────────
        private Integer    vatRate;          // 0, 5, 8, 10
        private BigDecimal vatAmount;

        // ── Số lượng & tổng ──────────────────────────────────────────
        private BigDecimal quantity;
        private BigDecimal subtotal;
        private String     notes;

        private List<IngredientUsed> ingredientsUsed;
    }

    // ─────────────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class IngredientUsed {
        private Long       ingredientId;
        private String     ingredientName;
        private String     ingredientImageUrl;
        private BigDecimal quantityUsed;
        private String     unit;
    }
}