// entity/pos/PosDiscountProgram.java
package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_discount_program")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosDiscountProgram {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    // ── Kỳ tính chi tiêu (qualify) ────────────────────────────────
    @Column(nullable = false) private Long qualifyFrom;  // epoch ms
    @Column(nullable = false) private Long qualifyTo;

    // ── Kỳ áp dụng giảm (apply) ──────────────────────────────────
    @Column(nullable = false) private Long applyFrom;
    @Column(nullable = false) private Long applyTo;

    // ── Điều kiện đủ điều kiện ────────────────────────────────────
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal minSpend;                    // VD: 1,000,000đ

    // ── Hạn mức giảm tối đa mỗi khách ────────────────────────────
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal maxDiscountPerCustomer;      // VD: 200,000đ

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProgramStatus status = ProgramStatus.DRAFT;

    @Column private Long createdAt;
    @Column private Long endedAt;      // manual end
    @Column(length = 200) private String endedReason;

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<PosDiscountOption> options = new java.util.ArrayList<>();

    @PrePersist void onCreate() { createdAt = System.currentTimeMillis(); }

    public enum ProgramStatus { DRAFT, ACTIVE, ENDED, CANCELLED }
}