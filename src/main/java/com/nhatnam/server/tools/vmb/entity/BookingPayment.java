package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một lần thu tiền cho 1 booking. Booking có N payment records; tổng cộng
 * dồn = paidAmount ở Booking, đối chiếu với tổng vé để suy ra
 * PAID / PARTIAL / UNPAID.
 *
 * ── Thu batch (Phase G) ────────────────────────────────
 * Khi user chọn nhiều booking và thu 1 lượt, service tạo N record cùng
 * {@code storedName} (cùng file, cùng ảnh biên nhận) nhưng khác {@code
 * bookingId}. Xóa file phải kiểm ref-count (không xóa nếu >0 record khác
 * còn dùng).
 *
 * ── Ảnh chứng từ (nullable) ────────────────────────────
 * Không phải payment nào cũng có ảnh (thu qua chuyển khoản có thể chỉ ghi
 * chú). Để null nếu chưa upload.
 */
@Entity
@Table(name = "vmb_booking_payment", indexes = {
        @Index(name = "idx_vmb_payment_booking", columnList = "booking_id"),
        @Index(name = "idx_vmb_payment_created", columnList = "created_at"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** Số tiền thu (String, cùng convention với price). Bắt buộc. */
    @Column(name = "amount", nullable = false, length = 30)
    private String amount;

    /** Ghi chú ngắn, VD "Thu đợt 1", "Chuyển khoản Vietcombank". */
    @Column(name = "note", length = 500)
    private String note;

    // ── Ảnh biên nhận (nullable) ────────────────────────

    @Column(name = "stored_name", length = 100)
    private String storedName;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "created_by", length = 60)
    private String createdBy;
}
