package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lịch sử lệnh đã đóng. Append-only theo (account_id, ticket).
 * MT5 chỉ gửi ticket lớn hơn latestClosedTicket đã lưu trên server.
 */
@Entity
@Table(
        name = "mt5_bot_closed_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mt5_bot_closed_acc_ticket",
                columnNames = {"account_id", "ticket"}
        ),
        indexes = {
                @Index(name = "idx_mt5_bot_closed_acc_time", columnList = "account_id,close_time"),
                @Index(name = "idx_mt5_bot_closed_ticket", columnList = "ticket")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mt5BotClosedOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "ticket", nullable = false)
    private Long ticket;

    @Column(name = "symbol", length = 32)
    private String symbol;

    @Column(name = "direction", length = 8)
    private String direction;

    @Column(name = "volume")
    private Double volume;

    @Column(name = "open_price")
    private Double openPrice;

    @Column(name = "close_price")
    private Double closePrice;

    @Column(name = "profit")
    private Double profit;

    @Column(name = "commission")
    private Double commission;

    @Column(name = "swap")
    private Double swap;

    @Column(name = "fee")
    private Double fee;

    @Column(name = "open_time")
    private LocalDateTime openTime;

    @Column(name = "close_time")
    private LocalDateTime closeTime;

    @Column(name = "comment", length = 255)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
