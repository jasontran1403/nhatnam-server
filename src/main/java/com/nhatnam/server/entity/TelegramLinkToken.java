package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Mã liên kết dùng một lần.
 *
 * Flow:
 *   1. User bấm "Liên kết Telegram" → BE tạo record này (token ngẫu nhiên, TTL vài phút)
 *   2. FE dẫn user tới https://t.me/&lt;bot&gt;?start=&lt;token&gt;
 *   3. Bot nhận /start &lt;token&gt; → tra record → lưu chat_id vào bảng telegram_link → xóa record này
 *
 * Nếu token hết hạn hoặc dùng rồi → bot báo lỗi, user bấm lại nút để lấy mã mới.
 */
@Entity
@Table(name = "telegram_link_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_tg_token_value", columnNames = "token"),
        indexes = {
                @Index(name = "idx_tg_token_user",    columnList = "user_id"),
                @Index(name = "idx_tg_token_expires", columnList = "expires_at"),
        })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TelegramLinkToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "expires_at", nullable = false)
    private Long expiresAt;
}
