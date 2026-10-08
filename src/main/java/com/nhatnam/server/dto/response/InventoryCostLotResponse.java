package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 1 lô giá vốn của nguyên liệu — hiển thị ở màn hình "Lô hàng". */
@Data
@Builder
public class InventoryCostLotResponse {

    private Long       id;
    private Long       ingredientId;
    private String     ingredientName;
    private String     unit;

    /** Mã phiếu nhập tạo ra lô, hoặc "OPENING" (tồn kho cũ backfill). */
    private String     sourceRef;

    /** true nếu là lô tồn kho cũ (OPENING). */
    private boolean    opening;

    private BigDecimal unitCost;           // giá vốn 1 đơn vị của lô
    private BigDecimal originalQuantity;   // SL nhập ban đầu
    private BigDecimal remainingQuantity;  // SL còn lại
    private BigDecimal remainingValue;     // remainingQuantity * unitCost

    private Long       expiryDate;         // epoch-millis, nullable
    private Long       createdAt;          // epoch-millis (0 với lô OPENING)

    /** true khi đã xuất hết (remainingQuantity = 0). */
    private boolean    depleted;

    /** true khi HSD đã qua. */
    private boolean    expired;
}