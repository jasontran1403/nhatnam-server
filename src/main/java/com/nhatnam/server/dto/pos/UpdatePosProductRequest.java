package com.nhatnam.server.dto.pos;

import lombok.*;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdatePosProductRequest {
    private String name;
    private String description;
    private String imageUrl;
    private Long categoryId;
    private BigDecimal basePrice;
    private Boolean isActive;
    private Integer displayOrder;
    private Integer vatPercent;
    private Boolean isShopeeFood;  // nullable — chỉ gửi khi thay đổi
    private Boolean isGrabFood;
    private BigDecimal shopeePrice;
    private BigDecimal grabPrice;
}
