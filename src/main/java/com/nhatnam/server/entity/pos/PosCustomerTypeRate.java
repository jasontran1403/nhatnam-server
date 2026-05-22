package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "pos_customer_type_rate",
        uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "type_code"}))
public class PosCustomerTypeRate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "type_code", nullable = false, length = 20)
    private String typeCode;   // "KLE", "CTV", "CTVV", hoặc bất kỳ type mới

    @Column(name = "type_label", nullable = false, length = 100)
    private String typeLabel;  // "Khách lẻ", "Cộng tác viên"...

    @Column(name = "accum_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal accumRate;  // tích lũy cho người mua thuộc type này

    @Column(name = "referral_rate", nullable = false, precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal referralRate = new BigDecimal("0.05"); // bonus cho người giới thiệu

    @Builder.Default
    @Column(name = "created_at")
    private Long createdAt = System.currentTimeMillis();

    @Builder.Default
    @Column(name = "updated_at")
    private Long updatedAt = System.currentTimeMillis();
}