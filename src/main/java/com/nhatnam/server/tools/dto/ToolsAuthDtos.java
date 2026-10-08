package com.nhatnam.server.tools.dto;

import lombok.*;

/**
 * Gom mọi DTO của auth + quản lý user vào một file cho dễ theo dõi.
 * Đều là record-style (immutable getter/setter), không có logic.
 */
public final class ToolsAuthDtos {

    private ToolsAuthDtos() {}

    // ── Đăng nhập ───────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class LoginRequest {
        private String username;
        private String password;
        /** true → token dài hạn (TTL mặc định của backend). false → token 12 giờ. */
        private Boolean remember;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LoginResponse {
        private String token;
        private String username;
        private String displayName;
        private Boolean admin;
        /** Hạn dùng token, epoch millis — FE hiển thị đếm ngược ở màn Hồ sơ */
        private Long expiresAt;
    }

    // ── Quản lý user ────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String displayName;
        private Boolean admin;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class UpdateUserRequest {
        /** null = không đổi. Chuỗi rỗng vẫn tính là "đổi thành rỗng" cho displayName. */
        private String displayName;
        private Boolean admin;
        private Boolean active;
        /** Đổi mật khẩu; null = giữ nguyên. */
        private String newPassword;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UserView {
        private Long id;
        private String username;
        private String displayName;
        private Boolean admin;
        private Boolean active;
        private Long createdAt;
        private Long updatedAt;
    }
}
