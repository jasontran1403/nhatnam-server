package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request tạo tài khoản SELLER mới (từ SuperAdmin).
 * Mỗi SELLER mới sẽ được tự động tạo 1 PosStore riêng.
 */
@Data
public class CreateSellerRequest {
    @NotBlank private String username;
    @NotBlank @Email private String email;
    @NotBlank @Size(min = 6) private String password;
    @NotBlank private String fullName;
    @NotBlank private String phoneNumber;

    // Thông tin store riêng của seller
    @NotBlank private String storeName;
    private String storeAddress;
    private String storePhone;
}
