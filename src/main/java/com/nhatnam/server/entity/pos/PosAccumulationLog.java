package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "pos_accumulation_log")
public class PosAccumulationLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private PosCustomer customer;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long orderId;

    /** Chi tiêu net của đơn này (chưa VAT, sau discount) */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal spendNet;

    /** Tháng tích lũy YYYY-MM */
    @Column(nullable = false, length = 7)
    private String month;

    /** Loại: SELF (bản thân) | REFERRAL_BONUS (hoa hồng từ người được giới thiệu) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LogType logType;

    /** Nếu là REFERRAL_BONUS, lưu id customer đã chi tiêu */
    private Long referredCustomerId;

    private Long createdAt;

    public enum LogType { SELF, REFERRAL_BONUS }
}