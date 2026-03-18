// entity/pos/PosDiscountOption.java
package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_discount_option")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosDiscountOption {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private PosDiscountProgram program;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    // % hoặc số tiền tuyệt đối
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    // Giảm tối đa 1 lần dùng (nullable = không giới hạn)
    @Column(precision = 10, scale = 2)
    private BigDecimal maxPerUse;

    @Column(length = 100)
    private String label;   // VD: "Giảm 10% trên món (tối đa 20k)"

    public enum DiscountType {
        PERCENT_BILL,   // % tổng bill
        FIXED_BILL,     // tiền cố định tổng bill
        PERCENT_ITEM,   // % 1 món được chọn
        FIXED_ITEM,     // tiền cố định 1 món được chọn
    }

    public boolean isItemType() {
        return discountType == DiscountType.PERCENT_ITEM
                || discountType == DiscountType.FIXED_ITEM;
    }
}