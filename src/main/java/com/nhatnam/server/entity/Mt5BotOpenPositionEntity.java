package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Snapshot các lệnh đang mở. Replace toàn bộ theo account mỗi lần sync từ MT5.
 */
@Entity
@Table(
        name = "mt5_bot_open_position",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mt5_bot_open_pos_acc_ticket",
                columnNames = {"account_id", "ticket"}
        ),
        indexes = @Index(name = "idx_mt5_bot_open_pos_acc", columnList = "account_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mt5BotOpenPositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "ticket", nullable = false)
    private Long ticket;

    @Column(name = "symbol", length = 32)
    private String symbol;

    /** BUY / SELL */
    @Column(name = "direction", length = 8)
    private String direction;

    @Column(name = "volume")
    private Double volume;

    @Column(name = "open_price")
    private Double openPrice;

    @Column(name = "current_price")
    private Double currentPrice;

    @Column(name = "profit")
    private Double profit;

    @Column(name = "swap")
    private Double swap;

    @Column(name = "open_time")
    private LocalDateTime openTime;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() { updatedAt = LocalDateTime.now(); }
}
