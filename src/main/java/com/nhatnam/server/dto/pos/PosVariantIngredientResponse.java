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
    /** Giá addon áp dụng khi bán TẠI QUÁN = override ?? giá mặc định NL. */
    private BigDecimal addonPrice;

    /** Giá bán tại quán do món này set riêng. null = đang dùng giá mặc định. */
    private BigDecimal addonPriceOverride;

    /** Giá bán trên ShopeeFood. null = chưa set → dùng giá tại quán. */
    private BigDecimal addonPriceShopee;

    /** Giá bán trên GrabFood. null = chưa set → dùng giá tại quán. */
    private BigDecimal addonPriceGrab;

    /** Giá addon mặc định khai báo ở nguyên liệu (để UI hiển thị gợi ý). */
    private BigDecimal defaultAddonPrice;
    private BigDecimal unitPerPack;      // 1 bịch = ? miếng (tỷ lệ quy đổi xé lẻ)
    private Boolean    looseSaleEnabled; // Ý 2
    private Boolean    hotSaleEnabled;   // Ý 1
    private String     unit;             // đơn vị chính (Túi/Kg...)
    private String     looseUnit;        // đơn vị nhỏ nhất khi xé lẻ (Miếng/Kg...)
}
