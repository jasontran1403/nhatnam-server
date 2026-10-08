package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.entity.*;
import com.nhatnam.server.tools.vmb.enumtype.VmbFeeType;

import java.util.List;

/**
 * Entity → IO. URL sinh runtime để dễ đổi prefix mà không migrate DB.
 */
public final class VmbMapper {

    private VmbMapper() {}

    private static final String FILE_PREFIX = "/vmb-files/";

    public static String urlOf(String storedName) {
        return (storedName == null || storedName.isBlank()) ? null : FILE_PREFIX + storedName;
    }

    // ── BOOKING ────────────────────────────────────────────────

    public static BookingIO toBookingIO(Booking b) {
        // Tách list ticketFiles: 1 booking-face (ticket=null) + map ticketId→face
        TicketFileIO bookingFace = null;
        java.util.Map<Long, TicketFileIO> byTicket = new java.util.HashMap<>();
        for (TicketFile f : b.getTicketFiles()) {
            if (f.getTicket() == null) {
                if (bookingFace == null) bookingFace = toTicketFileIO(f);
            } else {
                byTicket.putIfAbsent(f.getTicket().getId(), toTicketFileIO(f));
            }
        }

        List<TicketIO> tickets = b.getTickets().stream()
                .map(t -> toTicketIO(t, byTicket.get(t.getId())))
                .toList();

        return BookingIO.builder()
                .id(b.getId())
                .kind(b.getKind())
                .airlineCode(b.getAirlineCode())
                .bookingCode(b.getBookingCode())
                .routeStr(b.getRouteStr())
                .currency(b.getCurrency())
                .exchangeRate(b.getExchangeRate())
                .note(b.getNote())
                .saleDate(b.getSaleDate())
                .sharedTicketFace(b.isSharedFace())
                .bookingFace(bookingFace)
                .paymentStatus(b.getPaymentStatus())
                .paidAmount(b.getPaidAmount())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .segments(mapList(b.getSegments(), VmbMapper::toSegmentIO))
                .tickets(tickets)
                .invoices(mapList(b.getInvoices(), VmbMapper::toInvoiceIO))
                .bookingProofs(mapList(b.getBookingProofs(), VmbMapper::toBookingProofIO))
                .build();
    }

    public static SegmentIO toSegmentIO(BookingSegment s) {
        return SegmentIO.builder()
                .id(s.getId())
                .fromCode(s.getFromCode())
                .toCode(s.getToCode())
                .departLocalMs(s.getDepartLocalMs())
                .segOrder(s.getSegOrder())
                .build();
    }

    public static TicketIO toTicketIO(Ticket t, TicketFileIO ticketFace) {
        return TicketIO.builder()
                .id(t.getId())
                .passengerName(t.getPassengerName())
                .companyId(t.getCompanyId())
                .ticketNumber(t.getTicketNumber())
                .basePrice(t.getBasePrice())
                .collectionFee(t.getCollectionFee())
                .issuanceFee(t.getIssuanceFee())
                .paidStatus(t.getPaidStatus())
                .note(t.getNote())
                .ticketFace(ticketFace)
                .fees(mapList(t.getFees(), VmbMapper::toTicketFeeIO))
                .build();
    }

    public static TicketFeeIO toTicketFeeIO(TicketFee f) {
        return TicketFeeIO.builder()
                .id(f.getId())
                .feeType(f.getFeeType())
                .feeTypeLabel(VmbFeeType.labelOf(f.getFeeType()))
                .amount(f.getAmount())
                .note(f.getNote())
                .orderIdx(f.getOrderIdx())
                .build();
    }

    public static TicketFileIO toTicketFileIO(TicketFile f) {
        return TicketFileIO.builder()
                .id(f.getId())
                .ticketId(f.getTicket() != null ? f.getTicket().getId() : null)
                .originalName(f.getOriginalName())
                .contentType(f.getContentType())
                .sizeBytes(f.getSizeBytes())
                .url(urlOf(f.getStoredName()))
                .build();
    }

    public static BookingProofIO toBookingProofIO(BookingProof p) {
        return BookingProofIO.builder()
                .id(p.getId())
                .ticketId(p.getTicket() != null ? p.getTicket().getId() : null)
                .originalName(p.getOriginalName())
                .contentType(p.getContentType())
                .sizeBytes(p.getSizeBytes())
                .url(urlOf(p.getStoredName()))
                .createdAt(p.getCreatedAt())
                .build();
    }

    public static InvoiceIO toInvoiceIO(Invoice inv) {
        // Empty ticket set = chung cả booking. Trả về [] (không null) cho FE
        // dễ xử lý.
        List<Long> ticketIds = inv.getTickets() == null
                ? List.of()
                : inv.getTickets().stream().map(Ticket::getId).sorted().toList();

        return InvoiceIO.builder()
                .id(inv.getId())
                .ticketIds(ticketIds)
                .status(inv.getStatus())
                .note(inv.getNote())
                .draftUrl(urlOf(inv.getDraftFile()))
                .draftOriginal(inv.getDraftOriginal())
                .issuedUrl(urlOf(inv.getIssuedFile()))
                .issuedOriginal(inv.getIssuedOriginal())
                .adjustmentUrl(urlOf(inv.getAdjustmentFile()))
                .adjustmentOriginal(inv.getAdjustmentOriginal())
                .adjustmentRecordUrl(urlOf(inv.getAdjustmentRecordFile()))
                .adjustmentRecordOriginal(inv.getAdjustmentRecordOriginal())
                .createdAt(inv.getCreatedAt())
                .updatedAt(inv.getUpdatedAt())
                .build();
    }

    // ── PASSENGER ────────────────────────────────────────────

    public static PassengerIO toPassengerIO(Passenger p) {
        List<PassengerDocumentIO> docs = mapList(p.getDocuments(), VmbMapper::toPassengerDocumentIO);

        String nearest = null;
        for (PassengerDocumentIO d : docs) {
            String exp = d.getExpiryDate();
            if (exp == null || exp.isBlank()) continue;
            if (nearest == null || exp.compareTo(nearest) < 0) nearest = exp;
        }

        return PassengerIO.builder()
                .id(p.getId())
                .companyId(p.getCompany() != null ? p.getCompany().getId()   : null)
                .companyName(p.getCompany() != null ? p.getCompany().getName() : null)
                .fullName(p.getFullName())
                .dob(p.getDob())
                .gender(p.getGender())
                .documents(docs)
                .nearestExpiry(nearest)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .membershipCount(p.getMembershipCards() != null ? p.getMembershipCards().size() : 0)
                .build();
    }

    public static PassengerDocumentIO toPassengerDocumentIO(PassengerDocument d) {
        return PassengerDocumentIO.builder()
                .id(d.getId())
                .passengerId(d.getPassenger() != null ? d.getPassenger().getId() : null)
                .type(d.getType())
                .docNumber(d.getDocNumber())
                .nationality(d.getNationality())
                .issueDate(d.getIssueDate())
                .expiryDate(d.getExpiryDate())
                .fileUrl(urlOf(d.getStoredFile()))
                .fileOriginal(d.getOriginalFile())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    public static CompanyIO toCompanyIO(Company c) {
        return CompanyIO.builder()
                .id(c.getId())
                .name(c.getName())
                .build();
    }

    public static MembershipCardIO toMembershipIO(MembershipCard m) {
        return MembershipCardIO.builder()
                .id(m.getId())
                .passengerName(m.getPassengerName())
                .cardNumber(m.getCardNumber())
                .airlineCode(m.getAirlineCode())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }

    // ── LOOKUP ───────────────────────────────────────────────

    public static LookupIO toLookupIO(LookupEntry e) {
        return LookupIO.builder()
                .id(e.getId())
                .type(e.getType())
                .keyword(e.getKeyword())
                .details(e.getDetails())
                .loginUrl(e.getLoginUrl())
                .loginUsername(e.getLoginUsername())
                .agencyCode(e.getAgencyCode())
                .passwordMasked((e.getPasswordEnc() != null && !e.getPasswordEnc().isBlank()) ? "••••••••" : null)
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private static <T, R> List<R> mapList(List<T> src, java.util.function.Function<T, R> fn) {
        if (src == null) return List.of();
        return src.stream().map(fn).toList();
    }
}