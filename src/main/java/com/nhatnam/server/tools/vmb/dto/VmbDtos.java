package com.nhatnam.server.tools.vmb.dto;

import lombok.*;

import java.util.List;

/**
 * Gom mọi request/response DTO của khu VMB vào một file. Không có logic —
 * chỉ chở dữ liệu qua tầng controller ↔ FE.
 *
 * ── 2026-09-19 bổ sung ─────────────────────────────────────
 *   • {@link TicketFeeIO}          — 1 dòng phí chi tiết.
 *   • {@link BookingProofIO}       — 1 file bằng chứng.
 *   • {@link FeeTypeIO}            — 1 cặp code+label VN cho dropdown FE.
 *   • {@link InvoiceIO}#ticketIds  — thay {@code ticketId} single.
 *   • {@link TicketIO}#fees        — list phí thay {@code serviceFee}.
 *   • {@link BookingIO}#bookingProofs — list bằng chứng của booking.
 */
public final class VmbDtos {

    private VmbDtos() {}

    // ══════════════════════════════════════════════════════════════
    // BOOKING & TICKET
    // ══════════════════════════════════════════════════════════════

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SegmentIO {
        private Long id;
        private String fromCode;
        private String toCode;
        private Long departLocalMs;
        private Integer segOrder;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TicketFileIO {
        private Long id;
        private Long ticketId;
        private String originalName;
        private String contentType;
        private Long sizeBytes;
        private String url;
    }

    /**
     * 1 dòng phí trên vé. Cùng feeType có thể lặp nhiều dòng — FE nhận diện
     * qua {@code id} khi update.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TicketFeeIO {
        /** Null khi create; not null khi update. */
        private Long id;
        /** Mã trong {@link com.nhatnam.server.tools.vmb.enumtype.VmbFeeType}. */
        private String feeType;
        /** Label VN — BE bơm để FE khỏi lookup. */
        private String feeTypeLabel;
        private String amount;
        private String note;
        private Integer orderIdx;
    }

    /**
     * 1 file bằng chứng của booking. ticketId null = chung; not null = riêng khách.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BookingProofIO {
        private Long id;
        private Long ticketId;
        private String originalName;
        private String contentType;
        private Long sizeBytes;
        private String url;
        private Long createdAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class InvoiceIO {
        private Long id;
        /**
         * Danh sách vé mà hóa đơn áp cho.
         *   Empty → chung cả booking.
         *   Non-empty → chỉ những vé này.
         */
        private List<Long> ticketIds;
        private String status;
        private String note;
        private String draftUrl;
        private String draftOriginal;
        private String issuedUrl;
        private String issuedOriginal;
        private String adjustmentUrl;
        private String adjustmentOriginal;
        private String adjustmentRecordUrl;
        private String adjustmentRecordOriginal;
        private Long createdAt;
        private Long updatedAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TicketIO {
        private Long id;
        private String passengerName;
        private Long companyId;
        private String companyName;
        private String companyShortName;
        private String ticketNumber;
        private String basePrice;
        private String collectionFee;
        private String issuanceFee;
        private String paidStatus;
        private String note;
        private TicketFileIO ticketFace;
        /** List phí chi tiết (đổi vé/hoàn/hành lý…). */
        private List<TicketFeeIO> fees;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BookingIO {
        private Long id;
        private String kind;
        private String airlineCode;
        private String bookingCode;
        private String routeStr;
        private String currency;
        private String exchangeRate;
        private String note;
        private Long saleDate;
        /** CHỈ ảnh hưởng mặt vé (TicketFile), KHÔNG ảnh hưởng BookingProof/Invoice. */
        private Boolean sharedTicketFace;
        private TicketFileIO bookingFace;
        private String paymentStatus;
        private String paidAmount;
        private Long createdAt;
        private Long updatedAt;
        private List<SegmentIO> segments;
        private List<TicketIO> tickets;
        private List<InvoiceIO> invoices;
        /** File bằng chứng của booking. Xem {@link BookingProofIO}. */
        private List<BookingProofIO> bookingProofs;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SaveBookingRequest {
        private String kind;
        private String airlineCode;
        private String bookingCode;
        private String routeStr;
        private String currency;
        private String exchangeRate;
        private String note;
        private Long saleDate;
        private Boolean sharedTicketFace;
        private List<SegmentIO> segments;
        private List<TicketIO> tickets;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BookingTotals {
        private String currency;
        private String withCollection;   // Sum(basePrice + collectionFee)
        private String serviceFee;       // Sum(all TicketFee.amount) — tính lại từ list fees mới
        private String subTotal;         // Sum(basePrice + collectionFee + fees)
        private String issuanceFee;
        private String grandTotal;
        private Integer ticketCount;
    }

    /** Dropdown loại phí cho FE. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FeeTypeIO {
        private String code;
        private String label;
    }

    // ══════════════════════════════════════════════════════════════
    // PASSENGER & COMPANY & MEMBERSHIP & DOCUMENT
    // ══════════════════════════════════════════════════════════════

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CompanyIO {
        private Long id;
        private String name;
        private String shortName;
        private String address;
        private String taxId;
        private String phone;
        private String email;
        private Long parentId;
        private Long createdAt;
        private Long updatedAt;
        private Long passengerCount;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SaveCompanyRequest {
        private String name;
        private String shortName;
        private String address;
        private String taxId;
        private String phone;
        private String email;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PaymentIO {
        private Long id;
        private Long bookingId;
        private String amount;
        private String note;
        private String fileUrl;
        private String fileOriginal;
        private Long createdAt;
        private String createdBy;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MembershipCardIO {
        private Long id;
        private String passengerName;
        private String cardNumber;
        private String airlineCode;
        private Long createdAt;
        private Long updatedAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PassengerDocumentIO {
        private Long id;
        private Long passengerId;
        private String type;
        private String docNumber;
        private String nationality;
        private String issueDate;
        private String expiryDate;
        private String fileUrl;
        private String fileOriginal;
        private Long createdAt;
        private Long updatedAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SavePassengerDocumentRequest {
        private String docNumber;
        private String nationality;
        private String issueDate;
        private String expiryDate;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PassengerIO {
        private Long id;
        private Long companyId;
        private String companyName;
        private String fullName;
        private String dob;
        private String gender;
        private List<PassengerDocumentIO> documents;
        private String nearestExpiry;
        private Long createdAt;
        private Long updatedAt;
        private Integer membershipCount;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SavePassengerRequest {
        private Long companyId;
        private String newCompanyName;
        private String fullName;
        private String dob;
        private String gender;
    }

    // ══════════════════════════════════════════════════════════════
    // LOOKUP
    // ══════════════════════════════════════════════════════════════

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LookupIO {
        private Long id;
        private String type;
        private String keyword;
        private String details;
        private String loginUrl;
        private String loginUsername;
        private String agencyCode;
        private String passwordMasked;
        private Long createdAt;
        private Long updatedAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SaveLookupRequest {
        private String type;
        private String keyword;
        private String details;
        private String loginUrl;
        private String loginUsername;
        private String agencyCode;
        private String password;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class RevealPasswordRequest {
        private String code;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RevealPasswordResponse {
        private String password;
        private String state;
        private Long   lockedUntil;
        private Integer remaining;
    }
}