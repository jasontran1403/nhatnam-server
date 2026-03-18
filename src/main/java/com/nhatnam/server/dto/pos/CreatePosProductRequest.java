package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreatePosProductRequest {
    @NotBlank(message = "Tên sản phẩm không được trống")
    private String name;
    private String description;
    private String imageUrl;

    @NotNull(message = "Category không được trống")
    private Long categoryId;

    @NotNull(message = "Giá không được trống")
    @DecimalMin(value = "0.0", message = "Giá phải >= 0")
    private BigDecimal basePrice;

    @Builder.Default
    private Integer vatPercent = 0;

    @Builder.Default
    private Boolean isShopeeFood = false;

    @Builder.Default
    private Boolean isGrabFood = false;

    private BigDecimal shopeePrice;  // nullable — chỉ gửi khi isShopeeFood=true

    private BigDecimal grabPrice;    // nullable — chỉ gửi khi isGrabFood=true

    private Integer displayOrder;
}