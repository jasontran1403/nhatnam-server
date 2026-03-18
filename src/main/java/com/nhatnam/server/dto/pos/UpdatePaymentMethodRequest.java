package com.nhatnam.server.dto.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdatePaymentMethodRequest {
    @NotBlank(message = "Phương thức thanh toán không được để trống")
    @Pattern(regexp = "CASH|TRANSFER", message = "Phương thức thanh toán phải là CASH hoặc TRANSFER")
    private String paymentMethod;
}