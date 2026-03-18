package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OpenShiftRequest {
    @NotBlank(message = "Tên nhân viên không được trống")
    private String staffName;

    // Mệnh giá tiền đầu ca — bắt buộc
    @NotEmpty(message = "Phải nhập mệnh giá tiền đầu ca")
    private List<DenominationItem> openDenominations;

    // Kho đầu ca — chỉ bắt buộc nếu isFirstShiftOfDay = true
    // Nếu không phải ca đầu ngày, server tự lấy từ ca trước
    private List<InventoryItem> openInventory;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DenominationItem {
        private Integer denomination; // 500/1000/.../500000
        private Integer quantity;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InventoryItem {
        private Long ingredientId;
        private Integer packQuantity;
        private Integer unitQuantity;
    }
}
