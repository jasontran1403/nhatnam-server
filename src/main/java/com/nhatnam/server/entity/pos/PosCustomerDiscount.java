// entity/pos/PosCustomerDiscount.java
package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_customer_discount",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"customer_id", "program_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosCustomerDiscount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private PosCustomer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private PosDiscountProgram program;

    // null = chưa chọn option
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id")
    private PosDiscountOption selectedOption;

    // Tiền đã được giảm từ chương trình này
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal budgetUsed = BigDecimal.ZERO;

    @Column private Long qualifiedAt;   // thời điểm đủ điều kiện (job chạy)
    @Column private Long optionChosenAt; // thời điểm chọn option
}