package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * "Mặt vé" — file PDF hoặc ảnh chụp vé máy bay.
 *
 * ── Scope linh hoạt (2026-09-15) ───────────────────────────
 * Trước đây bảng này gắn cứng vào Ticket (1 vé N file). Nay:
 *   {@code booking}  — LUÔN có (không null).
 *   {@code ticket}   — CÓ THỂ null.
 *
 * Ý nghĩa:
 *   ticket == null → mặt vé CHUNG cho cả booking (Booking#sharedTicketFace = true).
 *   ticket != null → mặt vé RIÊNG cho vé đó (Booking#sharedTicketFace = false).
 *
 * Ràng buộc (được service kiểm, không dùng UNIQUE ở DB vì null trong UNIQUE):
 *   - Booking.sharedTicketFace = true  → tối đa 1 row với ticket = null,
 *                                        và không có row nào ticket != null.
 *   - Booking.sharedTicketFace = false → 0..1 row cho mỗi ticket_id,
 *                                        và không có row nào ticket = null.
 *
 * File thật lưu trên đĩa (thư mục cấu hình bởi tools.vmb.storage-dir); cột
 * {@code storedName} là tên vật lý (UUID.ext), còn {@code originalName} là tên
 * hiển thị cho người dùng.
 *
 * Bảng: vmb_ticket_file (giữ tên để không phải rename bảng khi migrate).
 */
@Entity
@Table(name = "vmb_ticket_file", indexes = {
        @Index(name = "idx_vmb_ticket_file_booking", columnList = "booking_id"),
        @Index(name = "idx_vmb_ticket_file_ticket",  columnList = "ticket_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Booking chủ. LUÔN có kể cả khi ticket != null (dữ liệu cũ thời chỉ gắn
     * theo Ticket được backfill sang bằng ticket.booking — xem VmbBackfillRunner).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** Null = mặt vé chung; not null = mặt vé riêng cho vé đó */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Column(name = "stored_name", nullable = false, length = 100)
    private String storedName;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}