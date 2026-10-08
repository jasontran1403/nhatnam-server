package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Một Vé cho MỘT hành khách trong một Booking. Cùng booking có thể có nhiều
 * ticket (đoàn 5 người → 5 ticket, chung mã đặt chỗ).
 *
 * ── Giá tiền ────────────────────────────────────────────────────────
 * Lưu dạng String chứ không BigDecimal:
 *   - Người dùng nhập USD có 2 số lẻ, VND thì nguyên. Kiểu String giữ đúng
 *     dạng người dùng gõ, tránh 24500.00 bị thành 24500.
 *   - Các phép cộng (thành tiền, giá bán, quy đổi VND) tính ở FE bằng thư
 *     viện money.js để hiển thị đúng — BE không cộng trừ, chỉ lưu.
 *
 * Các cột giá:
 *   basePrice       = giá bán chưa tính phí thu hộ (dùng làm hóa đơn)
 *   collectionFee   = phí thu hộ
 *   issuanceFee     = phí xuất vé
 *   fees            = danh sách phí phát sinh (đổi vé/hoàn vé/hành lý/ăn uống…).
 *                     Có thể có 0..N dòng, cùng feeType lặp cũng OK.
 *                     Xem {@link TicketFee}.
 *
 * ── 2026-09-19 refactor ────────────────────────────────────────────
 * BỎ cột {@code service_fee} — trước đây serviceFee = "phí dịch vụ / đổi /
 * hoàn" tùy Booking.kind. Nay các loại phí đó đều nằm trong {@link #fees}
 * (mã tương ứng CHANGE_TICKET, REFUND_TICKET, và nhiều loại khác). Migration
 * chuyển service_fee cũ → 1 dòng TicketFee với feeType suy ra từ Booking.kind.
 * Xem {@code VmbBackfillRunner#migrateServiceFeeToTicketFees}.
 *
 * ── Trạng thái ─────────────────────────────────────────────────────
 * paidStatus:   PENDING | PAID
 *
 * Trạng thái hóa đơn KHÔNG lưu ở Ticket vì 1 vé có thể liên kết nhiều Invoice
 * với trạng thái khác nhau (many-to-many). Badge trên UI hiển thị "trạng thái
 * nghiêm trọng nhất" của tập Invoice mà ticket này thuộc về.
 *
 * Bảng: vmb_ticket
 */
@Entity
@Table(name = "vmb_ticket", indexes = {
        @Index(name = "idx_vmb_ticket_booking", columnList = "booking_id"),
        @Index(name = "idx_vmb_ticket_number",  columnList = "ticket_number")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "passenger_name", length = 200)
    private String passengerName;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "ticket_number", length = 40)
    private String ticketNumber;

    // ── Giá (String để giữ nguyên format người dùng nhập) ──────

    @Column(name = "base_price",      length = 30) private String basePrice;
    @Column(name = "collection_fee",  length = 30) private String collectionFee;
    @Column(name = "issuance_fee",    length = 30) private String issuanceFee;

    /** PENDING | PAID */
    @Column(name = "paid_status", nullable = false, length = 20)
    @Builder.Default
    private String paidStatus = "PENDING";

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    // ── Quan hệ con ───────────────────────────────────────────────

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @Builder.Default
    private List<TicketFile> files = new ArrayList<>();

    /**
     * KHÔNG map ngược Invoice vì quan hệ Invoice ↔ Ticket là many-to-many
     * (owner phía Invoice giữ join table {@code vmb_invoice_ticket}). Nếu cần
     * "invoices của 1 vé" query qua {@code InvoiceRepository.findByTicketId}.
     */

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIdx ASC, id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<TicketFee> fees = new ArrayList<>();

    public void addFee(TicketFee f) { f.setTicket(this); fees.add(f); }
}