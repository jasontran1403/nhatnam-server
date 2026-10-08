package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * File "bằng chứng" cho booking — ảnh/PDF thông tin đặt chỗ, biên nhận,
 * xác nhận thay đổi... Đây là file thứ 4 khác {@link TicketFile}, {@link Invoice}
 * (draft/issued/adjustment/record).
 *
 * ── Khác biệt với {@link TicketFile} ──────────────────────────
 *   TicketFile      : 1 file duy nhất (mặt vé), bị chi phối bởi
 *                     {@code Booking.sharedTicketFace}.
 *   BookingProof    : N file, KHÔNG dùng cờ sharedTicketFace. 1 booking có
 *                     thể có mixed scope:
 *                       - {@code ticket == null} → bằng chứng chung cả booking
 *                       - {@code ticket != null} → bằng chứng riêng của 1 khách
 *                     Cả 2 tồn tại song song trong cùng booking cũng được.
 *
 * Ở FE, khi Xuất trình "Thông tin booking", sẽ gộp cho mỗi khách:
 *   files_hiển_thị_cho_khách_X = (mọi BookingProof có ticket=null của booking)
 *                              ∪ (mọi BookingProof có ticket=X)
 * Rồi group các khách có tập file giống nhau lại → 1 group tải chung.
 *
 * Bảng: vmb_booking_proof
 */
@Entity
@Table(name = "vmb_booking_proof", indexes = {
        @Index(name = "idx_vmb_booking_proof_booking", columnList = "booking_id"),
        @Index(name = "idx_vmb_booking_proof_ticket",  columnList = "ticket_id"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** Null = chung cả booking. Not null = riêng của 1 khách. */
    @ManyToOne(fetch = FetchType.LAZY)
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