package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Công ty của khách.
 *
 * ── Phase G — mở rộng ─────────────────────────────────────
 * Thêm địa chỉ, MST, SĐT, email, tên viết tắt, và cột parent_id cho chi
 * nhánh (self-reference). Chi nhánh KHÔNG có bảng riêng — chỉ là 1 hàng
 * Company có parent_id trỏ về công ty mẹ. Cây chỉ sâu 2 tầng (chi nhánh
 * của chi nhánh không cho phép — kiểm tra ở service).
 *
 * ── Tên viết tắt (short_name) ────────────────────────────
 * Hiển thị ở cột "Công ty" trên bảng vé để không đè text quá dài. Nếu để
 * trống, FE tự lấy chữ cái đầu mỗi từ của name làm fallback.
 *
 * ── Bất biến ──────────────────────────────────────────────
 *   - name unique (không phân biệt chữ hoa/thường ở service)
 *   - taxId unique nếu có
 *   - Xóa công ty mẹ CASCADE xóa các chi nhánh (xem CompanyService.delete)
 */
@Entity
@Table(name = "vmb_company", indexes = {
        @Index(name = "uk_vmb_company_name",   columnList = "name", unique = true),
        @Index(name = "idx_vmb_company_parent", columnList = "parent_id"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Tên viết tắt hiển thị bảng vé — tối đa 30 ký tự. Nullable. */
    @Column(name = "short_name", length = 30)
    private String shortName;

    @Column(name = "address", length = 400)
    private String address;

    /** Mã số thuế — cho phép null nhưng nếu có phải unique (kiểm ở service). */
    @Column(name = "tax_id", length = 30)
    private String taxId;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 100)
    private String email;

    /**
     * ID công ty mẹ. NULL = công ty gốc, không null = chi nhánh.
     * Không dùng @ManyToOne để tránh N+1 lúc list; chỉ giữ id.
     */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}
