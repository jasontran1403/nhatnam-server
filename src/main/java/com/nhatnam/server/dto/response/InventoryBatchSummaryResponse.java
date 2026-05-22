package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class InventoryBatchSummaryResponse {
    private Long   id;
    private String batchCode;
    private String action;       // "IMPORT" | "EXPORT" | "ADJUST"
    private String supplierRef;
    private String receiptImageUrl;
    private String createdByName;
    private Long   createdAt;
    private int    totalItems;   // số dòng log
    private BigDecimal totalImportAmount;
}


