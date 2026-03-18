package com.nhatnam.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateOrderRequest {
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String shippingAddress;
    private Integer discountRate = 0;
    private String type;

    @NotBlank private String paymentMethod; // CASH | BANK_TRANSFER
    private String notes;

    @NotEmpty @Valid
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        @NotNull private Long productId;
        private Long variantId;

        @NotNull @DecimalMin("0.01")
        private BigDecimal quantity;

        // "BASE" | "TIER" | "DISCOUNT_PERCENT"
        @NotBlank private String priceMode = "TIER";

        // null = auto-detect theo quantity
        private Long tierId;

        @Min(1) @Max(100)
        private Integer discountPercent;

        private String notes;

        @JsonProperty("sentUnitPrice")
        private BigDecimal sentUnitPrice;

        @JsonProperty("orderType")
        private String orderType;
    }
}
