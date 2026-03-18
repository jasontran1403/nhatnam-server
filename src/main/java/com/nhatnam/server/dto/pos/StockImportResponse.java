package com.nhatnam.server.dto.pos;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StockImportResponse {
    private Long id;
    private Long ingredientId;
    private String ingredientName;
    private Integer packQty;
    private Long importedAt;
}