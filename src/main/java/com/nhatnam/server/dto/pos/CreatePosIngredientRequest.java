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
    @Min(value = 0, message = "Tỷ lệ quy đổi phải > 0")
    private BigDecimal unitPerPack;
    private Integer displayOrder;

    private IngredientType ingredientType;  // default MAIN
    private BigDecimal addonPrice;          // default 0
    private String unit;

    // Ý 1 — bán món nóng
    private Boolean hotSaleEnabled;
    private Integer hotSalesPerBag;
    private BigDecimal hotQtyPerSale;
    private String hotSaleUnit;

    // Ý 2 — bán xé lẻ (tỷ lệ quy đổi = unitPerPack, đơn vị bịch = unit)
    private Boolean looseSaleEnabled;
    private String  looseUnit;
}