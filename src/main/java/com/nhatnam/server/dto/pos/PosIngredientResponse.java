package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.IngredientType;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosIngredientResponse {
    private Long id;
    private String name;
    private String imageUrl;
    private BigDecimal unitPerPack; // Số lẻ trong 1 bịch
    private Boolean isActive;
    private Integer displayOrder;
    private IngredientType ingredientType;
    private BigDecimal addonPrice;
    private String unit;

    // Ý 1 — bán món nóng
    private Boolean hotSaleEnabled;
    private Integer hotSalesPerBag;
    private BigDecimal hotQtyPerSale;
    private String hotSaleUnit;

    // Ý 2 — bán xé lẻ
    private Boolean looseSaleEnabled;
    private String  looseUnit;
}
