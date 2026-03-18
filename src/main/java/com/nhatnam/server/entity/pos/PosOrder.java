// entity/pos/PosOrder.java — thêm customer snapshot + discount fields
package com.nhatnam.server.entity.pos;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderSource;
import com.nhatnam.server.enumtype.PosOrderStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_order")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosOrder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", unique = true, nullable = false)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_source", nullable = false)
    private OrderSource orderSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PosOrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    // ── Discount snapshot ─────────────────────────────────────────
    @Column(precision = 12, scale = 2) @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(length = 200)
    private String discountNote;

    // ── Tổng sau giảm ─────────────────────────────────────────────
    @Column(name = "final_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalAmount;

    // ── Customer snapshot ─────────────────────────────────────────
    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "payment_method", length = 30) @Builder.Default
    private String paymentMethod = "CASH";

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude @Builder.Default
    private List<PosOrderItem> items = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosStore store;
}