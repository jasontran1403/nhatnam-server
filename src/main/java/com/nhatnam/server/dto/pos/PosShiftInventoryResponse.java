package com.nhatnam.server.dto.pos;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftInventoryResponse {
    private Long ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private Integer unitPerPack;
    private Integer packQuantity;
    private Integer unitQuantity;
    // Tổng quy về lẻ = packQuantity * unitPerPack + unitQuantity
    private Integer totalUnits;
}
