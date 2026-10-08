package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Sự kiện tin tức. EA sync lên server, dedupe theo (name, event_time).
 * Chỉ lưu tin xuất hiện kể từ lúc EA attach — tin cũ (trước attach) không lưu.
 */
@Entity
@Table(
        name = "mt5_news_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mt5_news_name_time",
                columnNames = {"name", "event_time"}
        ),
        indexes = {
                @Index(name = "idx_mt5_news_event_time", columnList = "event_time")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mt5NewsEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Giờ tin (giờ MT5 server, không có timezone). */
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    /** 1=LOW, 2=MEDIUM, 3=HIGH. */
    @Column(name = "importance", nullable = false)
    private Integer importance;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}