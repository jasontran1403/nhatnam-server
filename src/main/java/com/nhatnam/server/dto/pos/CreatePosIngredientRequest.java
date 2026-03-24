package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.IngredientType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreatePosIngredientRequest {
    @NotBlank(message = "Tên nguyên liệu không được trống")
    private String name;
    private String imageUrl;
    @Min(value = 1, message = "Số lẻ trong 1 bịch phải >= 1")
    private Integer unitPerPack;
    private Integer displayOrder;

    private IngredientType ingredientType;  // default MAIN
    private BigDecimal addonPrice;          // default 0
    private String unit;
}