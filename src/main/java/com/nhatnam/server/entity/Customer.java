// entity/Customer.java — thêm fields còn thiếu cho Sỉ/Lẻ
// Chỉ thêm các fields MỚI, giữ nguyên fields cũ

package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Fields cũ (giữ nguyên) ───────────────────────────────────

    @Column(nullable = false, length = 15, unique = true)
    private String phone;

    @Column(length = 100)
    private String name;

    @Column(length = 150)
    private String email;

    @Column(name = "discount_rate", nullable = false)
    @Builder.Default
    private Integer discountRate = 0;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<CustomerAddress> addresses = new ArrayList<>();

    // ── Fields MỚI cho Sỉ/Lẻ ────────────────────────────────────

    /**
     * Mã khách hàng rút gọn để tìm kiếm khi tạo đơn
     * Công ty: NOK, NOK-01, NOK-02 | Khách lẻ: KLE
     */
    @Column(name = "customer_code", length = 30, unique = true)
    private String customerCode;

    /**
     * Loại KH: COMPANY (doanh nghiệp/Sỉ) | RETAIL (khách lẻ)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", length = 20)
    @Builder.Default
    private CustomerType customerType = CustomerType.RETAIL;

    /** Tên doanh nghiệp đầy đủ (nullable với KH lẻ) */
    @Column(name = "company_name", length = 200)
    private String companyName;

    /** Tên rút gọn / alias */
    @Column(name = "short_name", length = 100)
    private String shortName;

    /** Mã số thuế */
    @Column(name = "tax_code", length = 20)
    private String taxCode;

    /** Địa chỉ công ty / địa chỉ chính */
    @Column(name = "address", length = 300)
    private String address;

    /** Địa chỉ giao hàng mặc định */
    @Column(name = "delivery_address", length = 300)
    private String deliveryAddress;

    /** Chủ / Người liên hệ */
    @Column(name = "contact_name", length = 100)
    private String contactName;

    /** Ngày sinh người liên hệ — format yyyy-MM-dd */
    @Column(name = "date_of_birth", length = 10)
    private String dateOfBirth;

    @PrePersist
    void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum CustomerType {
        COMPANY,  // Doanh nghiệp (Sỉ)
        RETAIL    // Khách lẻ
    }
}