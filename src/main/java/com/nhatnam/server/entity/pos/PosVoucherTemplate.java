package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "pos_voucher_template")
public class PosVoucherTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VoucherType voucherType;

    /** Số tiền giảm cố định */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount;

    /** Số credit cần để đổi 1 voucher này */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal creditCost;

    @Column(nullable = false)
    private boolean active = true;

    private Long createdAt;

    public enum VoucherType { FIXED_AMOUNT }
}