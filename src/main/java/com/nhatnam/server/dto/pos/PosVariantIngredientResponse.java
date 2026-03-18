package com.nhatnam.server.dto.pos;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder
public class PosVariantIngredientResponse {
    private Long       id;
    private Long       ingredientId;
    private String     ingredientName;
    private String     ingredientImageUrl;
    private BigDecimal stockDeductPerUnit;
    private Integer    maxSelectableCount;
    private String     subGroupTag;
    private Integer    subGroupMaxSelect;
    private Integer    displayOrder;
    private BigDecimal addonPrice;   // ← THÊM: giá addon của nguyên liệu này
}
