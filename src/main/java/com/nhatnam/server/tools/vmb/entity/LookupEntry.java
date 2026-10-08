package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một mục Tra cứu. Có 2 dạng:
 *
 *   PLAIN   — thông tin bình thường: chỉ có {@code keyword} + {@code details}.
 *   ACCOUNT — tài khoản đăng nhập: {@code keyword} + {@code loginUrl} +
 *             {@code loginUsername} + {@code passwordEnc} (mã hóa AES-GCM) +
 *             {@code agencyCode} (mã đại lý, tùy chọn).
 *
 * Mật khẩu KHÔNG lưu plaintext — mã hóa bằng khóa AES ở BE. Reveal cần TOTP.
 *
 * Bảng: vmb_lookup
 */
@Entity
@Table(name = "vmb_lookup", indexes = {
        @Index(name = "idx_vmb_lookup_kw",   columnList = "keyword"),
        @Index(name = "idx_vmb_lookup_type", columnList = "type")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LookupEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PLAIN | ACCOUNT */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "keyword", nullable = false, length = 200)
    private String keyword;

    // ── Cho PLAIN ────────────────────────────────────────────────

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    // ── Cho ACCOUNT ─────────────────────────────────────────────

    @Column(name = "login_url", length = 500)
    private String loginUrl;

    @Column(name = "login_username", length = 200)
    private String loginUsername;

    /**
     * Mật khẩu đã mã hóa AES-GCM, base64-URL. Không bao giờ trả về API list.
     * Chỉ endpoint reveal (đã kiểm TOTP) mới decrypt và trả plaintext.
     */
    @Column(name = "password_enc", columnDefinition = "TEXT")
    private String passwordEnc;

    /**
     * Mã đại lý (tùy chọn) — ví dụ mã 6 ký tự các hãng bay cấp cho đại lý.
     * Không nhạy cảm như password, không mã hóa; hiển thị được ngay ở card.
     */
    @Column(name = "agency_code", length = 60)
    private String agencyCode;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
