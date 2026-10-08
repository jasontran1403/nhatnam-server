package com.nhatnam.server.dto.pos;

import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftInventoryResponse {
    private Long ingredientId;
    private String ingredientName;
    private String ingredientImageUrl;
    private Integer unitPerPack;
    private Integer packQuantity;
    private BigDecimal unitQuantity;   // ← BigDecimal thay vì Integer
    private double     totalUnits;     // ← double để chứa phần thập phân
    @Builder.Default
    private Integer importPackQty = 0;   // tổng bịch nhập trong ca
    @Builder.Default
    private BigDecimal soldQty = BigDecimal.ZERO;   // CHỈ bán nguyên bịch

    // ── Thông tin dòng phụ "bán món nóng" (xé lẻ từ bịch) ──────────
    @Builder.Default
    private Integer hotPortions = 0;    // số lần đã bán món nóng
    @Builder.Default
    private Integer hotBags = 0;        // số bịch đã xé (ceil)
    @Builder.Default
    private Integer hotLeftover = 0;    // số lần còn dư trong bịch đang mở
    @Builder.Default
    private BigDecimal hotQtyTotal = BigDecimal.ZERO;  // tổng khối lượng đã dùng
    private String hotSaleUnit;         // đơn vị món nóng, vd "Kg"

    // ── Thông tin dòng phụ "xé bán lẻ" (Ý 2) ──────────────────────
    @Builder.Default
    private BigDecimal loosePieces = BigDecimal.ZERO;    // số lượng đã bán lẻ (đơn vị nhỏ nhất)
    @Builder.Default
    private Integer loosePacks = 0;                      // số túi đã xé (ceil)
    @Builder.Default
    private BigDecimal looseLeftover = BigDecimal.ZERO;  // còn dư trong túi đang xé
    private String looseUnit;           // đơn vị nhỏ nhất khi xé lẻ, vd "Miếng"
    private String packUnit;            // đơn vị tính của nguyên liệu, vd "Túi"
}