package com.nhatnam.server.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngredientResponse {
    private Long id;
    private String name;
    private String unit;
    private BigDecimal stockQuantity;
    private String imageUrl;
    private Long createdAt;
    private Long updatedAt;
}