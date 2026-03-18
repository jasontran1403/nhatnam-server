package com.nhatnam.server.dto.pos;

import lombok.Data;

import java.util.List;

@Data
public class StockImportRequest {
    private List<ImportItem> items;

    @Data
    public static class ImportItem {
        private Long ingredientId;
        private Integer packQty;
    }
}