package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "pos_evoucher")
public class PosEVoucher {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private PosCustomer customer;

    @Column(nullable = false)
    private Long storeId;

    /** Credit note đã dùng để redeem */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_note_id", nullable = false)
    private PosCreditNote creditNote;

    /** Template voucher áp dụng */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private PosVoucherTemplate template;

    /** Giá trị credit đã dùng để đổi voucher */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal creditUsed;

    /** Giá trị voucher thực tế (theo template) */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal voucherValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EVoucherStatus status; // ACTIVE, USED, EXPIRED

    /** Hạn sử dụng 1 tháng từ ngày redeem */
    @Column(nullable = false)
    private Long expiredAt;

    /** Đơn hàng đã dùng voucher này */
    private Long usedOrderId;
    private Long usedAt;
    private Long createdAt;

    public enum EVoucherStatus { ACTIVE, USED, EXPIRED }
}