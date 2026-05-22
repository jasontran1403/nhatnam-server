package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "pos_evoucher_usage_log")
public class PosEVoucherUsageLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private PosEVoucher voucher;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Long storeId;

    /** Giá trị gốc của voucher */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal voucherValue;

    /** Số tiền thực tế được giảm (có thể < voucherValue nếu đơn nhỏ hơn) */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal actualDiscountApplied;

    /** finalAmount của đơn trước khi áp voucher */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal orderAmountBeforeVoucher;

    @Column(nullable = false)
    private Long usedAt;

    /** Loại voucher: PERCENT / FIXED_AMOUNT / ITEM_DISCOUNT */
    @Column(length = 20)
    private String voucherType;

    @Column(length = 50)
    private String voucherCode;
}