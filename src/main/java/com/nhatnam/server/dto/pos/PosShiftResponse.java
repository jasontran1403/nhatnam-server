package com.nhatnam.server.dto.pos;

import com.nhatnam.server.enumtype.ShiftStatus;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftResponse {
    private Long id;
    private String staffName;
    private ShiftStatus status;
    private String shiftDate;
    private Boolean isFirstShiftOfDay;

    private Long openTime;
    private Long closeTime;

    private BigDecimal openingCash;
    private BigDecimal closingCash;
    private BigDecimal transferAmount;
    private String note;

    private List<PosShiftDenominationResponse> openDenominations;
    private List<PosShiftDenominationResponse> closeDenominations;
    private List<PosShiftInventoryResponse> openInventory;
    private List<PosShiftInventoryResponse> closeInventory;

    // Thống kê nhanh
    private Integer totalOrders;
    private BigDecimal totalRevenue;
}