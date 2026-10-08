package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 1 giấy tờ tùy thân của 1 {@link Passenger}. Mỗi (passenger, type) tối đa
 * 1 dòng — được service enforce ở tầng ứng dụng thay vì UNIQUE ở DB.
 *
 * Type:
 *   CCCD     — Căn cước công dân VN. Không có {@code nationality} (mặc định VNM).
 *   PASSPORT — Hộ chiếu. Có {@code nationality} là mã ISO-3 (VNM, JPN, USA...).
 *
 * Ngày cấp / ngày hết hạn lưu "YYYY-MM-DD" (String) tương tự các entity khác
 * để tránh vấn đề múi giờ.
 *
 * File ảnh/PDF: {@code storedFile} là tên vật lý (UUID.ext) — file thật ở
 * thư mục tools.vmb.storage-dir; {@code originalFile} là tên hiển thị.
 *
 * Bảng: vmb_passenger_document
 */
@Entity
@Table(name = "vmb_passenger_document", indexes = {
        @Index(name = "idx_vmb_pax_doc_pax",  columnList = "passenger_id"),
        @Index(name = "idx_vmb_pax_doc_type", columnList = "type"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PassengerDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    /** "CCCD" | "PASSPORT" */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** Số CCCD / số hộ chiếu */
    @Column(name = "doc_number", length = 40)
    private String docNumber;

    /** Chỉ cho PASSPORT — ISO-3 uppercase (VNM, USA, JPN...) */
    @Column(name = "nationality", length = 3)
    private String nationality;

    /** "YYYY-MM-DD" */
    @Column(name = "issue_date", length = 10)
    private String issueDate;

    /** "YYYY-MM-DD" */
    @Column(name = "expiry_date", length = 10)
    private String expiryDate;

    @Column(name = "stored_file", length = 100)
    private String storedFile;

    @Column(name = "original_file", length = 255)
    private String originalFile;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public static final String TYPE_CCCD     = "CCCD";
    public static final String TYPE_PASSPORT = "PASSPORT";
}