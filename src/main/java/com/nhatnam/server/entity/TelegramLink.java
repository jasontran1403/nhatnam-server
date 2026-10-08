package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Liên kết 1-1 giữa user eOffice và tài khoản Telegram (chat_id).
 * Sau khi user chạy /start &lt;token&gt; với bot, chat_id được lưu ở đây.
 * Bot dùng chat_id này để nhắn tin private (không qua group).
 */
@Entity
@Table(name = "telegram_link",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tg_link_user",   columnNames = "user_id"),
                @UniqueConstraint(name = "uk_tg_link_chatid", columnNames = "chat_id"),
        },
        indexes = {
                @Index(name = "idx_tg_link_chatid", columnList = "chat_id"),
        })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TelegramLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** Telegram username (không có @), tùy user có set hay không */
    @Column(name = "tg_username", length = 100)
    private String telegramUsername;

    /** Tên hiển thị Telegram lúc link (first_name + last_name) */
    @Column(name = "tg_display_name", length = 200)
    private String telegramDisplayName;

    @Column(name = "linked_at", nullable = false)
    private Long linkedAt;
}
