package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "pos_accumulation_record")
public class PosAccumulationRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private PosCustomer customer;

    @Column(nullable = false)
    private Long storeId;

    /** Tháng tích lũy: format YYYY-MM (VD: 2026-03) */
    @Column(nullable = false, length = 7)
    private String month;

    /** Tổng chi tiêu trong tháng (chưa VAT, sau discount) */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalSpendNet;

    /** Số credit được tích lũy = totalSpendNet × 5% */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal creditAmount;

    /** Đã được cộng vào credit note chưa */
    @Column(nullable = false)
    private boolean settled = false;

    private Long settledAt;
    private Long createdAt;
    private Long updatedAt;
}