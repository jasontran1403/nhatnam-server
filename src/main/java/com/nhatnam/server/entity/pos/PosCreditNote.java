package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "pos_credit_note")
public class PosCreditNote {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private PosCustomer customer;

    @Column(nullable = false)
    private Long storeId;

    /** Tháng phát sinh (YYYY-MM) */
    @Column(nullable = false, length = 7)
    private String sourceMonth;

    /** Loại: SPEND (tích lũy chi tiêu) | REFERRAL (từ giới thiệu) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditNoteType type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal remainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditNoteStatus status; // ACTIVE, PARTIALLY_USED, EXHAUSTED, EXPIRED

    /** Hết hạn sau 6 tháng kể từ ngày cấp */
    @Column(nullable = false)
    private Long expiredAt;

    private Long createdAt;
    private Long updatedAt;

    public enum CreditNoteType { SPEND, REFERRAL }
    public enum CreditNoteStatus { ACTIVE, PARTIALLY_USED, EXHAUSTED, EXPIRED }
}