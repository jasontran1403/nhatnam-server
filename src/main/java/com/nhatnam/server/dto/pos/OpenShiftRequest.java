package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OpenShiftRequest {

    @NotBlank(message = "Tên nhân viên không được trống")
    private String staffName;

    @NotEmpty(message = "Phải nhập mệnh giá tiền đầu ca")
    private List<DenominationItem> openDenominations;

    private List<InventoryItem> openInventory;

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
