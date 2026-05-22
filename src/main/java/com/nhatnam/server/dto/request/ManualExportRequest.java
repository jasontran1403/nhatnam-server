package com.nhatnam.server.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

// ══════════════════════════════════════════════════════════════
// MANUAL IMPORT (đã có, giữ nguyên — thêm setter receiptImageUrl)
// ══════════════════════════════════════════════════════════════
// (file này bổ sung 2 request mới bên dưới)

// ── Xuất kho thủ công ─────────────────────────────────────────
@Data
public class ManualExportRequest {

    /** Lý do xuất kho. VD: "Cho đơn hàng khách A", "Hỏng hóc" */
    private String reason;

    private List<ExportItem> items;

    private Long   supplierId;    // FK tới Supplier (optional)
    private String supplierName;  // snapshot tên NCC
    private String note;          // lý do xuất kho (đổi tên từ reason)

    @Data
    public static class ExportItem {
        private Long ingredientId;
        private BigDecimal quantity; // luôn dương, service sẽ negate
    }
}