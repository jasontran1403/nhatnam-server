package com.nhatnam.server.dto.pos;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO trả về cho 1 nguyên liệu đã chọn trong order item.
 * Bao gồm unitWeights để client hiển thị lại định lượng từng unit.
 */
@Data
@Builder
public class PosOrderItemIngredientResponse {

    private Long   ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private BigDecimal addonPrice;

    /** Số lần chọn (số unit). */
    private Integer selectedCount;

    /**
     * Định lượng mặc định mỗi unit (snapshot từ PosVariantIngredient.stockDeductPerUnit).
     * VD: 0.2 (kg/miếng).
     */
    private BigDecimal defaultDeductPerUnit;

    /**
     * Danh sách định lượng từng unit (nếu user đã override).
     * null = không override, dùng defaultDeductPerUnit × selectedCount.
     * VD: [0.31, 0.29, 0.32]
     */
    private List<BigDecimal> unitWeights;

    /** Tổng lượng trừ kho = sum(unitWeights) hoặc selectedCount × defaultDeductPerUnit. */
    private BigDecimal quantityUsed;
}