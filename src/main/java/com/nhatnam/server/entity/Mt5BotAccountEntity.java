package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Một MT5 account chạy bot. Server tự tạo row khi lần đầu nhận sync.
 * Unique key: (login, server).
 */
@Entity
@Table(
        name = "mt5_bot_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mt5_bot_account_login_server",
                columnNames = {"login", "server"}
        ),
        indexes = {
                @Index(name = "idx_mt5_bot_account_updated", columnList = "updated_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mt5BotAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Số login MT5 (ví dụ 463936671). */
    @Column(name = "login", nullable = false, length = 64)
    private String login;

    /** Server broker (ví dụ Exness-MT5Trial17). */
    @Column(name = "server", nullable = false, length = 128)
    private String server;

    /** Tên hiển thị do MT5 gửi (ví dụ Standard). */
    @Column(name = "name", length = 128)
    private String name;

    /** Lot bot sẽ dùng; 0 → bot bị force PAUSED. */
    @Column(name = "lot", nullable = false)
    @Builder.Default
    private Double lot = 0.0;

    /** RUNNING / PAUSED / STOPPING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    @Builder.Default
    private Mt5BotState state = Mt5BotState.PAUSED;

    /**
     * TicketId lớn nhất đã nhận từ MT5. MT5 chỉ gửi order có ticket > giá trị này.
     * Fallback 0 nếu chưa có → MT5 sẽ gửi toàn bộ history.
     */
    @Column(name = "latest_closed_ticket", nullable = false)
    @Builder.Default
    private Long latestClosedTicket = 0L;

    @Column(name = "balance")
    private Double balance;

    @Column(name = "equity")
    private Double equity;

    /** Tổng lot các lệnh đang mở (snapshot gần nhất). */
    @Column(name = "total_open_lot")
    private Double totalOpenLot;

    /** Tổng P/L lệnh đang mở (floating). */
    @Column(name = "total_open_profit")
    private Double totalOpenProfit;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    // ================================================================
    // AVOIDING_NEWS — flag phụ, SONG SONG với `state` (RUNNING/PAUSED/STOPPING).
    // Không đổi `state` khi tránh bão; chỉ bật cờ để UI hiển thị.
    // Bot tự quản lý (gửi active=true khi vào window, false khi hết).
    // ================================================================

    /** true khi bot đang chủ động tránh news impact cao. */
    @Column(name = "avoiding_news", nullable = false)
    @Builder.Default
    private Boolean avoidingNews = false;

    /** Tên tin đang tránh (hiển thị cho user). */
    @Column(name = "avoiding_news_title", length = 255)
    private String avoidingNewsTitle;

    /**
     * Thời điểm hết window chặn (UTC server time) — FE dùng để đếm ngược.
     * Khi now >= until, FE/BE tự coi avoidingNews = false.
     */
    @Column(name = "avoiding_news_until")
    private LocalDateTime avoidingNewsUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}