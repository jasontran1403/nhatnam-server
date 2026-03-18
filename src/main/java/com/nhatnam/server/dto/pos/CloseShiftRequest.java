package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CloseShiftRequest {
    // Mệnh giá tiền cuối ca — bắt buộc
    @NotEmpty(message = "Phải nhập mệnh giá tiền cuối ca")
    private List<OpenShiftRequest.DenominationItem> closeDenominations;

    // Kiểm kho cuối ca — bắt buộc
    @NotEmpty(message = "Phải nhập kho cuối ca")
    private List<OpenShiftRequest.InventoryItem> closeInventory;

    private BigDecimal transferAmount; // Chuyển khoản nếu có
    private String note;               // Chi phí phát sinh
}
