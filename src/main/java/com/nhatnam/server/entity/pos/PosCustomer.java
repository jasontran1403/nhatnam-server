// entity/pos/PosCustomer.java
package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_customer",
        uniqueConstraints = @UniqueConstraint(columnNames = "phone"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosCustomer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    // ── Thêm mới ──────────────────────────────────────────────────

    /** Ngày sinh — format: yyyy-MM-dd (nullable) */
    @Column(name = "date_of_birth", length = 10)
    private String dateOfBirth;

    /** Địa chỉ giao hàng (nullable) */
    @Column(name = "delivery_address", length = 300)
    private String deliveryAddress;

    /**
     * userId của người giới thiệu (nullable).
     * Lưu id của PosCustomer người giới thiệu, không phải userId hệ thống.
     * Tìm theo SĐT người giới thiệu → lấy id của PosCustomer đó.
     */
    @Column(name = "referred_by_customer_id")
    private Long referredByCustomerId;

    /** Tên người giới thiệu — snapshot để hiển thị không cần join */
    @Column(name = "referred_by_name", length = 100)
    private String referredByName;

    /** SĐT người giới thiệu — snapshot */
    @Column(name = "referred_by_phone", length = 20)
    private String referredByPhone;

    // ── Fields cũ ─────────────────────────────────────────────────

    @Column(nullable = false, precision = 16, scale = 2)
    @Builder.Default
    private BigDecimal totalSpend = BigDecimal.ZERO;

    @Column @Builder.Default
    private Long createdAt = System.currentTimeMillis();

    @Column @Builder.Default
    private Long updatedAt = System.currentTimeMillis();

    @PrePersist  void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate   void onUpdate() { updatedAt = System.currentTimeMillis(); }
}