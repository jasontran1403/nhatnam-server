package com.nhatnam.server.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class InvoiceDTO {
    private Long orderId;
    private String orderCode;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;
    private String notes;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private BigDecimal vatAmount;
    private String status;
    private String paymentStatus;
    private String paymentMethod;
    private Long createdAt;

    private String customerType;
    private String companyName;
    private String shortName;
    private String taxCode;
    private String contactName;
    private String deliveryAddress;
    private String companyPhone;
    private String companyAddress;

    Map<Integer, BigDecimal> vatBreakdown = new LinkedHashMap<>();

    // Danh sách sản phẩm trong đơn (giống OrderItemResponse)
    private List<Item> items;

    @Data
    @Builder
    public static class Item {
        private String productName;
        private String variantName;
        private String priceName;
        private BigDecimal unitPrice;
        private BigDecimal quantity;
        private BigDecimal subtotal;
        private String unit;
        private BigDecimal defaultPrice;
        private List<Ingredient> ingredientsUsed;
    }

    @Data
    @Builder
    public static class Ingredient {
        private String ingredientName;
        private BigDecimal quantityUsed;
        private String unit;
    }
}