package com.nhatnam.server.dto.pos;

import java.math.BigDecimal;
import java.util.List;

public class PosCustomerOrderDto {

    public record OrderSummary(
            Long   id,
            String orderCode,
            String orderSource,
            Long   createdAt,
            int    itemCount,
            BigDecimal totalAmount,      // tổng gốc (trước discount)
            BigDecimal discountAmount,
            BigDecimal finalAmount,      // net quán nhận
            BigDecimal platformFeeAmount,
            String paymentMethod,
            String status
    ) {}

    public record OrderDetail(
            Long       id,
            String     orderCode,
            String     appOrderCode,
            String     orderSource,
            Long       createdAt,
            String     paymentMethod,
            String     status,
            String     note,
            List<OrderItemDto> items,
            BigDecimal totalAmount,           // tạm tính (gross)
            BigDecimal discountAmount,
            String     discountNote,
            String     eVoucherCode,
            BigDecimal eVoucherDiscountAmount,
            BigDecimal totalVatAmount,
            BigDecimal platformFeeAmount,
            BigDecimal finalAmount,
            boolean    isAppOrder,            // ← THÊM
            BigDecimal platformRate
    ) {}

    public record OrderItemDto(
            Long       productId,
            String     productName,
            String     categoryName,
            int        quantity,
            BigDecimal basePrice,
            BigDecimal finalUnitPrice,
            BigDecimal subtotal,
            int        discountPercent,
            int        vatPercent,
            BigDecimal vatAmount,
            String     note,
            List<AddonDto> addons   // ← THÊM
    ) {}

    public record AddonDto(
            String ingredientName,
            int    selectedCount,
            BigDecimal addonPriceSnapshot
    ) {}

    public record PageResult(
            List<OrderSummary> content,
            int  page,
            int  size,
            long totalElements,
            int  totalPages,
            boolean hasNext
    ) {}
}

