package com.nhatnam.server.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ManualImportRequest {

    @NotEmpty(message = "Danh sách nguyên liệu không được rỗng")
    @Valid
    private List<ImportItem> items;

    @Data
    public static class ImportItem {

        @NotNull(message = "ingredientId không được null")
        private Long ingredientId;

        @NotNull(message = "quantity không được null")
        @Positive(message = "Số lượng phải lớn hơn 0")
        private BigDecimal quantity;

        /** epoch-millis, nullable — hạn dùng của lô nhập */
        private Long expiryDate;
    }
}