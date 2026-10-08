// src/main/java/com/nhatnam/server/dto/PosOrderExportDto.java
package com.nhatnam.server.dto;

import java.math.BigDecimal;

public record PosOrderExportDto(
        Long storeId, String storeName, String storeAddress, String storePhone,
        Long shiftId, String shiftStaffName, Long shiftOpenTime, Long shiftCloseTime,
        Long orderId, String orderCode, String customerName, String customerPhone,
        BigDecimal totalAmount, BigDecimal finalAmount,
        String orderSource, String paymentMethod, Long createdAt,
        String categoryName, String productName,
        BigDecimal basePrice, BigDecimal finalUnitPrice,
        BigDecimal discountPercent, Integer quantity, BigDecimal vatAmount,
        Long orderItemId,
        String ingredientName,
        BigDecimal ingredientQty,
        Integer ingredientSelectedCount,
        String ingredientUnitWeights,       // ← THÊM
        BigDecimal ingredientAddonPrice,    // giá addon gốc (null = không phải addon)
        BigDecimal ingredientAddonPriceNet  // giá addon sau giảm giá app + phí sàn
) {
    public double getDiscount() {
        if (totalAmount == null || finalAmount == null) return 0;
        return totalAmount.subtract(finalAmount).doubleValue();
    }

    public double getVat() {
        return vatAmount != null ? vatAmount.doubleValue() : 0;
    }

    public boolean hasItem() { return productName != null; }

    public boolean hasIngredient() { return ingredientName != null; }

    /** Nguyên liệu này được bán dưới dạng addon (có giá riêng). */
    public boolean isAddonIngredient() { return ingredientAddonPrice != null; }

    /** Giá addon quán thực nhận; fallback về giá gốc cho dữ liệu cũ. */
    public BigDecimal addonNetOrGross() {
        if (ingredientAddonPriceNet != null) return ingredientAddonPriceNet;
        return ingredientAddonPrice;
    }

    public double getIngredientDisplayQty() {
        if (ingredientUnitWeights != null && !ingredientUnitWeights.isBlank()
                && !ingredientUnitWeights.equals("[]")) {
            try {
                String trimmed = ingredientUnitWeights
                        .replace("[", "").replace("]", "").trim();
                if (!trimmed.isEmpty()) {
                    double sum = 0;
                    for (String part : trimmed.split(",")) {
                        sum += Double.parseDouble(part.trim());
                    }
                    return sum;
                }
            } catch (Exception e) {
                System.out.println("DEBUG Parse unitWeights failed: " + ingredientUnitWeights + " - " + e.getMessage());
            }
        }
        return ingredientSelectedCount != null ? ingredientSelectedCount : 0;
    }

}
