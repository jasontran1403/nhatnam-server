// dto/pos/PosOrderResponse.java
package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.OrderSource;
import com.nhatnam.server.enumtype.PosOrderStatus;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosOrderResponse {
    private Long id;
    private String orderCode;
    private Long shiftId;
    private String staffName;
    private String customerPhone;
    private String customerName;
    private OrderSource orderSource;
    private PosOrderStatus status;
    private String paymentMethod;

    private BigDecimal totalAmount;       // raw (trước discount, trước rate)
    private BigDecimal discountAmount;    // KM raw
    private BigDecimal finalAmount;       // net quán nhận = (total - discount) × (1 - rate)
    private BigDecimal totalVat;
    private String note;

    // ── Platform fee snapshot ────────────────────────────────────
    private BigDecimal platformRate;        // 0.3305
    private BigDecimal platformFeeAmount;   // (total - discount) × rate  [snapshot]

    private Long createdAt;
    private Long updatedAt;

    // ← Kept for backward compat nhưng tính từ snapshot
    private BigDecimal platformFee;   // = platformFeeAmount
    private BigDecimal netRevenue;    // = finalAmount

    private List<PosOrderItemResponse> items;
}