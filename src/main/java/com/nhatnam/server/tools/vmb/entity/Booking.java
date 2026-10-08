package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Một Booking = một mã đặt chỗ (PNR). Chứa nhiều {@link Ticket} và nhiều
 * {@link BookingSegment}.
 *
 * ── sharedTicketFace ───────────────────────────────────────
 * Cờ điều khiển scope của MẶT VÉ (TicketFile):
 *   TRUE  → chỉ có 1 mặt vé chung cho toàn booking (case Vietjet, VNA nội địa).
 *   FALSE → mỗi hành khách trong booking có mặt vé riêng.
 *
 * ⚠ Cờ này CHỈ ảnh hưởng {@link TicketFile}. KHÔNG ảnh hưởng {@link BookingProof}
 * (bằng chứng booking) và KHÔNG ảnh hưởng {@link Invoice} (hóa đơn).
 * BookingProof + Invoice tự chọn scope linh hoạt qua field {@code ticket} nullable.
 *
 * ── @BatchSize ─────────────────────────────────────────────
 * Đọc size() collection lazy trong service để trigger 1 IN query gộp thay vì
 * N+1.
 */
@Entity
@Table(name = "vmb_booking", indexes = {
        @Index(name = "idx_vmb_booking_code",  columnList = "booking_code"),
        @Index(name = "idx_vmb_booking_created", columnList = "created_at"),
        @Index(name = "idx_vmb_booking_sale",   columnList = "sale_date"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "airline_code", length = 5)
    private String airlineCode;

    @Column(name = "booking_code", length = 30)
    private String bookingCode;

    @Column(name = "route_str", length = 200)
    private String routeStr;

    @Column(name = "currency", nullable = false, length = 5)
    private String currency;

    @Column(name = "exchange_rate", length = 20)
    private String exchangeRate;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "sale_date")
    private Long saleDate;

    /**
     * Chỉ ảnh hưởng {@link TicketFile}. Xem javadoc lớp.
     */
    @Column(name = "shared_ticket_face", nullable = false)
    @Builder.Default
    private Boolean sharedTicketFace = Boolean.FALSE;

    @Column(name = "payment_status", nullable = false, length = 20)
    @Builder.Default
    private String paymentStatus = "UNPAID";

    @Column(name = "paid_amount", length = 30)
    private String paidAmount;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("segOrder ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<BookingSegment> segments = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<Ticket> tickets = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<TicketFile> ticketFiles = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<Invoice> invoices = new ArrayList<>();

    /**
     * Bằng chứng của booking — nhiều file, mixed scope (chung/riêng khách).
     * Xem {@link BookingProof}.
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<BookingProof> bookingProofs = new ArrayList<>();

    public void addSegment(BookingSegment s) { s.setBooking(this); segments.add(s); }
    public void addTicket(Ticket t)          { t.setBooking(this); tickets.add(t); }

    public boolean isSharedFace() {
        return Boolean.TRUE.equals(sharedTicketFace);
    }
}