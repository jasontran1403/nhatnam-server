package com.nhatnam.server.dto.pos;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosProductResponse {
    private Long id;
    private String name;
    private String description;
    private String imageUrl;
    private Boolean isActive;

    private Integer displayOrder;

    // Category
    private Long categoryId;
    private String categoryName;
    private Boolean singlePrice; // Kế thừa từ category

    // Giá gốc
    private BigDecimal basePrice;

    // 4 mức giá tự tính (hoặc 1 nếu singlePrice)
    // key = discountPercent (0/10/20/100), value = giá sau giảm
    private List<PriceOption> priceOptions;

    // Biến thể — null/empty = không có biến thể
    private List<PosVariantResponse> variants;
    private Boolean hasVariants;

    // App menu giá riêng
    private List<PosAppMenuResponse> appMenus;

    @Builder.Default
    private Integer vatPercent = 0;

    private Boolean isShopeeFood;
    private Boolean isGrabFood;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceOption {
        private Integer discountPercent; // 0, 10, 20, 100
        private BigDecimal price;        // Giá sau giảm
        private String label;            // "Giá gốc", "Giảm 10%", "Giảm 20%", "Miễn phí"
    }
}
