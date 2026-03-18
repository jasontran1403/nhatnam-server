package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.IngredientType;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosIngredientResponse {
    private Long id;
    private String name;
    private String imageUrl;
    private Integer unitPerPack; // Số lẻ trong 1 bịch
    private Boolean isActive;
    private Integer displayOrder;
    private IngredientType ingredientType;
    private BigDecimal addonPrice;
}
