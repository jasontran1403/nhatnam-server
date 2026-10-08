package com.nhatnam.server.tools.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Tài khoản truy cập khu Tiện ích nội bộ (/tools).
 *
 * TÁCH BIỆT hoàn toàn với bảng users của app chính (POS, Kế toán...): khu này
 * chỉ có mấy tài khoản dùng nội bộ, không muốn dính vào role-tree của app kia.
 *
 * Mật khẩu lưu ở dạng BCrypt (spring-security). Cột username unique và
 * lower-case ở tầng service.
 *
 * ── Cột totp_secret ─────────────────────────────────────────
 * Mỗi user có 1 secret 2FA riêng (base32, 32 ký tự). Dùng để xem mật khẩu
 * tài khoản trong tab Tra cứu ở khu VMB. Nếu request reveal có bearer token
 * hợp lệ, LookupService sẽ dùng secret của user đó; nếu không có token thì
 * fall back về secret ở tools.vmb.totp-secret trong application.yml (giữ
 * tương thích ngược cho ai chưa đăng nhập tools mà vẫn muốn dùng).
 */
@Entity
@Table(name = "tools_user", indexes = {
        @Index(name = "uk_tools_user_username", columnList = "username", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ToolsUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 60)
    private String username;

    /** BCrypt hash — KHÔNG bao giờ trả về API */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "is_admin", nullable = false)
    @Builder.Default
    private Boolean isAdmin = false;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Secret TOTP dạng base32 (A-Z, 2-7), thường 32 ký tự.
     * Nullable cho tương thích ngược — user cũ sẽ được backfill tự động lúc
     * khởi động qua {@link com.nhatnam.server.tools.config.ToolsBackfillRunner}.
     */
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
