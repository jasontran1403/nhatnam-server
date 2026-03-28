package com.nhatnam.server.dto.pos;

import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftInventoryResponse {
    private Long ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private Integer unitPerPack;
    private Integer packQuantity;
    private BigDecimal unitQuantity;   // ← BigDecimal thay vì Integer
    private double     totalUnits;     // ← double để chứa phần thập phân
    @Builder.Default
    private Integer importPackQty = 0;   // tổng bịch nhập trong ca
    @Builder.Default
    private BigDecimal soldQty = BigDecimal.ZERO;
}
