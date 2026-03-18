package com.nhatnam.server.dto.pos;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder
public class PosOrderItemIngredientResponse {
    private Long       ingredientId;
    private String     ingredientName;
    private String     ingredientImageUrl;
    private Integer    selectedCount;     // số lần chọn
    private BigDecimal quantityUsed;      // tổng trừ kho
}
