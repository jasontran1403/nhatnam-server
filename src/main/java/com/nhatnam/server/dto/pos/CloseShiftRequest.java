package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CloseShiftRequest {

    @NotEmpty(message = "Phải nhập mệnh giá tiền cuối ca")
    private List<DenominationItem> closeDenominations;

    private List<InventoryItem> closeInventory;
    private BigDecimal transferAmount;
    private String note;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DenominationItem {
        private Integer denomination;
        private Integer quantity;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InventoryItem {
        private Long       ingredientId;
        private Integer    packQuantity;
        private BigDecimal unitQuantity;   // ← BigDecimal
    }
}