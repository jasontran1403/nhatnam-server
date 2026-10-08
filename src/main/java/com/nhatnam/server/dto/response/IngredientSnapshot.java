package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class IngredientSnapshot {
    private Long ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private BigDecimal quantityUsed;
    private String unit;

    // Giá vốn theo FIFO (snapshot tại thời điểm bán)
    private BigDecimal costPrice;   // giá vốn bình quân FIFO / đơn vị
    private BigDecimal costAmount;  // tổng giá vốn dòng này

    // Chi tiết từng lô (nếu xử lý trên nhiều lô)
    private List<LotSnapshot> lots;

    @Data
    @Builder
    public static class LotSnapshot {
        private Long costLotId;
        private String sourceRef;   // mã batch / OPENING
        private BigDecimal unitCost;
        private BigDecimal quantity;
    }
}