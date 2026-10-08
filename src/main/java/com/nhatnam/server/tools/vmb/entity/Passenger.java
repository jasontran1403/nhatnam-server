package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hành khách. Thông tin nhân thân (họ tên, giới tính, ngày sinh) LƯU Ở ĐÂY —
 * đây là những thứ không thay đổi theo loại giấy tờ.
 *
 * ── Tách documents ra bảng riêng (2026-09-15) ──────────────
 * Trước đây các cột cccd_no, passport_no, expiry_date, cccd_file,
 * cccd_original, passport_file, passport_original đều nằm ở bảng passenger.
 * Vấn đề: 1 người có thể có CẢ CCCD lẫn hộ chiếu với NGÀY CẤP + NGÀY HẾT
 * HẠN khác nhau, không thể lưu chung 1 cột expiry_date.
 *
 * Nay các thứ đó chuyển sang {@link PassengerDocument} — mỗi loại giấy tờ
 * 1 dòng riêng, có ngày cấp / ngày hết hạn / số / ảnh riêng.
 *
 * Sau khi VmbBackfillRunner chạy xong 1 lần, các cột cũ sẽ bị drop khỏi
 * bảng vmb_passenger — Hibernate ddl-auto=update KHÔNG tự drop nên runner
 * chạy native SQL "ALTER TABLE ... DROP COLUMN IF EXISTS ...".
 *
 * Company là bắt buộc — khách lẻ vẫn thuộc "Khách lẻ" (bản ghi seed).
 *
 * Ngày sinh lưu "YYYY-MM-DD" (String) như cũ để tránh vấn đề múi giờ.
 *
 * Bảng: vmb_passenger
 */
@BatchSize(size = 50)
@Entity
@Table(name = "vmb_passenger", indexes = {
        @Index(name = "idx_vmb_pax_company", columnList = "company_id"),
        @Index(name = "idx_vmb_pax_name",    columnList = "full_name")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Passenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** "YYYY-MM-DD" */
    @Column(name = "dob", length = 10)
    private String dob;

    /** MALE | FEMALE | OTHER */
    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /**
     * ── 2026-09-15: đổi List → Set ─────────────────────────
     * Hibernate không cho fetch đồng thời nhiều "bag" (List) trong 1 query
     * → MultipleBagFetchException. Chuyển sang Set để có thể fetch cùng lúc
     * với {@link #documents} (vẫn là List) mà không lỗi.
     * Dùng LinkedHashSet để giữ thứ tự chèn; @OrderBy vẫn được tôn trọng.
     */
    @OneToMany(mappedBy = "passenger", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @Builder.Default
    private Set<MembershipCard> membershipCards = new LinkedHashSet<>();

    /**
     * Giấy tờ tùy thân — 0..2 dòng (CCCD, PASSPORT). Không dùng Map vì Hibernate
     * quản lý collection ổn hơn với List, và số phần tử tối đa chỉ 2.
     */
    @OneToMany(mappedBy = "passenger", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @Builder.Default
    private List<PassengerDocument> documents = new ArrayList<>();

    /** Trả về document theo type ("CCCD" | "PASSPORT"), hoặc null nếu chưa có */
    public PassengerDocument findDocument(String type) {
        if (type == null) return null;
        for (PassengerDocument d : documents) {
            if (type.equals(d.getType())) return d;
        }
        return null;
    }
}