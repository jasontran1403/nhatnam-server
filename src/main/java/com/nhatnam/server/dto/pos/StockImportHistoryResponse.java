package com.nhatnam.server.dto.pos;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Lịch sử 1 batch nhập kho (cùng importedAt timestamp)
 * Dùng cho endpoint GET /api/pos/shifts/stock-import/history
 */
@Data
@Builder
public class StockImportHistoryResponse {

    /** Thời điểm nhập (epoch ms) — dùng làm group key */
    private Long importedAt;

    /** Số thứ tự lần nhập trong ca (1, 2, 3...) */
    private Integer batchIndex;

    /** Tổng số dòng nguyên liệu trong lần nhập này */
    private Integer totalItems;

    /** Chi tiết từng nguyên liệu */
    private List<StockImportItemDetail> items;

    @Data
    @Builder
    public static class StockImportItemDetail {
        private Long id;               // PosShiftStockImport.id
        private Long ingredientId;
        private String ingredientName;
        private String ingredientImageUrl;
        private String ingredientType; // "MAIN" | "SUB"
        private Integer packQty;
        private Integer unitPerPack;
        private Long importedAt;
    }
}