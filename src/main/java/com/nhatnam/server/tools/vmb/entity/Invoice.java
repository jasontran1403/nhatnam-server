package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Một hóa đơn của 1 booking. 1 hóa đơn có thể áp cho nhiều vé (many-to-many
 * với {@link Ticket} qua bảng {@code vmb_invoice_ticket}).
 *
 * ── Ngữ nghĩa {@link #tickets} ─────────────────────────────────────
 *   Empty  → hóa đơn CHUNG cả booking (gộp tổng các vé).
 *   > 0    → hóa đơn cho những vé được chọn (subset của booking).
 *
 * Cho phép 1 ticket nằm trong nhiều Invoice (linh hoạt) — VD 1 hóa đơn nháp
 * gộp cả booking + 1 hóa đơn đã phát hành riêng cho 1 vé. Service không enforce
 * "1 vé 1 invoice".
 *
 * ── 2026-09-19 migration ───────────────────────────────────────────
 * Trước đây có cột {@code ticket_id} (nullable) chỉ 1 vé, hoặc null = chung.
 * Nay cột này bị drop, thay bằng join table. {@code VmbBackfillRunner} chuyển
 * data cũ:
 *   ticket_id != null → INSERT vào vmb_invoice_ticket
 *   ticket_id = null  → không insert gì (empty set = "chung cả booking")
 *
 * ── Vòng đời trạng thái ────────────────────────────────────────────
 *   NONE (không có bản ghi) → DRAFT (upload nháp) → ISSUED (upload đã phát hành,
 *   file nháp cũ bị XÓA) → ADJUSTED (upload hóa đơn điều chỉnh + biên bản đã
 *   ký, GIỮ nguyên file đã phát hành ban đầu).
 *
 * ── Các cột file ───────────────────────────────────────────────────
 *   draftFile         — file hóa đơn nháp (chỉ có khi status=DRAFT)
 *   issuedFile        — file hóa đơn đã phát hành (có khi ISSUED và ADJUSTED)
 *   adjustmentFile    — file hóa đơn điều chỉnh (chỉ có khi ADJUSTED)
 *   adjustmentRecordFile — file biên bản điều chỉnh đã ký (chỉ có khi ADJUSTED)
 *
 * Bảng: vmb_invoice
 */
@Entity
@Table(name = "vmb_invoice", indexes = {
        @Index(name = "idx_vmb_invoice_booking", columnList = "booking_id"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /**
     * Danh sách vé mà hóa đơn này áp dụng.
     *  - Empty → hóa đơn chung cả booking (gộp tổng các vé).
     *  - Non-empty → chỉ áp cho các vé trong set (subset OK, không cần đủ hết).
     *
     * LinkedHashSet giữ thứ tự chèn — FE có thể hiển thị đúng thứ tự user
     * đã chọn trong form.
     *
     * KHÔNG cascade DELETE Ticket (chỉ xóa row trong join table nếu invoice
     * hoặc ticket bị xóa — MySQL FK ON DELETE CASCADE trên join table).
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "vmb_invoice_ticket",
            joinColumns        = @JoinColumn(name = "invoice_id"),
            inverseJoinColumns = @JoinColumn(name = "ticket_id"),
            indexes = {
                    @Index(name = "idx_vmb_inv_tik_ticket", columnList = "ticket_id"),
            }
    )
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private Set<Ticket> tickets = new LinkedHashSet<>();

    /** DRAFT | ISSUED | ADJUSTED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "draft_file",             length = 100) private String draftFile;
    @Column(name = "draft_original",         length = 255) private String draftOriginal;

    @Column(name = "issued_file",            length = 100) private String issuedFile;
    @Column(name = "issued_original",        length = 255) private String issuedOriginal;

    @Column(name = "adjustment_file",        length = 100) private String adjustmentFile;
    @Column(name = "adjustment_original",    length = 255) private String adjustmentOriginal;

    @Column(name = "adjustment_record_file",     length = 100) private String adjustmentRecordFile;
    @Column(name = "adjustment_record_original", length = 255) private String adjustmentRecordOriginal;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}