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
        String ingredientUnitWeights        // ← THÊM
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
