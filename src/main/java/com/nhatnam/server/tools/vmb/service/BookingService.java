package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.entity.*;
import com.nhatnam.server.tools.vmb.enumtype.VmbFeeType;
import com.nhatnam.server.tools.vmb.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Nghiệp vụ cho tab Vé máy bay.
 *
 * ── Hydrate collections ─────────────────────────────────────
 * Đọc size() các collection lazy để trigger batch fetch (@BatchSize).
 * Sau refactor 2026-09-19, thêm hydrate cho: ticket.fees, booking.bookingProofs,
 * invoice.tickets.
 *
 * ── Vòng đời hóa đơn ────────────────────────────────────────
 * DRAFT → ISSUED : xóa draftFile trên đĩa + set draftFile=null
 * ISSUED → ADJUSTED : GIỮ issuedFile, thêm adjustmentFile + adjustmentRecordFile
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class BookingService {

    private static final Set<String> KINDS      = Set.of("NEW", "EXCHANGE", "REFUND", "SERVICE");
    private static final Set<String> CURRENCIES = Set.of("USD", "VND");
    private static final Set<String> PAID       = Set.of("PENDING", "PAID");
    private static final Set<String> INV_STATUS = Set.of("DRAFT", "ISSUED", "ADJUSTED");

    private final BookingRepository     bookingRepo;
    private final TicketRepository      ticketRepo;
    private final TicketFileRepository  ticketFileRepo;
    private final InvoiceRepository     invoiceRepo;
    private final CompanyRepository     companyRepo;
    private final PaymentService        paymentService;
    private final VmbStorageService     storage;

    // ── LIST & GET ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Booking> list(String q, Long fromSale, Long toSale, int page, int size) {
        Page<Booking> paged = bookingRepo.search(
                (q != null && !q.isBlank()) ? q.trim() : null,
                fromSale, toSale,
                PageRequest.of(page, Math.min(Math.max(size, 1), 200)));
        for (Booking b : paged.getContent()) hydrate(b);
        return paged;
    }

    /**
     * Tính totals. Sau refactor: tổng "phí dịch vụ" = sum của TICKET FEES
     * (thay vì cột serviceFee cũ đã bỏ).
     */
    @Transactional(readOnly = true)
    public java.util.List<BookingTotals> totals(String q, Long fromSale, Long toSale) {
        var all = bookingRepo.searchAll(
                (q != null && !q.isBlank()) ? q.trim() : null, fromSale, toSale);

        java.util.Map<String, double[]> acc = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();

        for (Booking b : all) {
            // Trigger fees fetch (BatchSize=50) — quan trọng cho totals!
            for (Ticket t : b.getTickets()) t.getFees().size();

            String cur = b.getCurrency() == null ? "VND" : b.getCurrency();
            double[] s = acc.computeIfAbsent(cur, k -> new double[5]);
            int cnt = counts.getOrDefault(cur, 0);
            for (Ticket t : b.getTickets()) {
                double base = parse(t.getBasePrice());
                double coll = parse(t.getCollectionFee());
                double svc  = sumFees(t.getFees());
                double iss  = parse(t.getIssuanceFee());
                s[0] += base + coll;
                s[1] += svc;
                s[2] += base + coll + svc;
                s[3] += iss;
                s[4] += base + coll + svc + iss;
                cnt++;
            }
            counts.put(cur, cnt);
        }

        for (String cur : new String[]{"VND", "USD"}) acc.computeIfAbsent(cur, k -> new double[5]);

        var out = new java.util.ArrayList<BookingTotals>();
        for (var e : acc.entrySet()) {
            String cur = e.getKey();
            double[] s = e.getValue();
            int digits = "USD".equals(cur) ? 2 : 0;
            out.add(BookingTotals.builder()
                    .currency(cur)
                    .withCollection(fmt(s[0], digits))
                    .serviceFee(fmt(s[1], digits))
                    .subTotal(fmt(s[2], digits))
                    .issuanceFee(fmt(s[3], digits))
                    .grandTotal(fmt(s[4], digits))
                    .ticketCount(counts.getOrDefault(cur, 0))
                    .build());
        }
        return out;
    }

    private static double sumFees(List<TicketFee> fees) {
        if (fees == null) return 0;
        double s = 0;
        for (TicketFee f : fees) s += parse(f.getAmount());
        return s;
    }

    private static double parse(String s) {
        if (s == null) return 0;
        String t = s.trim().replaceAll("\\s+", "");
        if (t.isEmpty()) return 0;

        // Bỏ ký tự lạ (chỉ giữ chữ số, dấu . , và dấu trừ)
        t = t.replaceAll("[^0-9.,\\-]", "");
        if (t.isEmpty() || t.equals("-")) return 0;

        int lastComma = t.lastIndexOf(',');
        int lastDot   = t.lastIndexOf('.');

        try {
            // Không có dấu phân cách nào → số nguyên
            if (lastComma < 0 && lastDot < 0) {
                return Double.parseDouble(t);
            }

            if (lastComma > lastDot) {
                // Dấu cuối là ','  →  xét phần sau nó
                String after = t.substring(lastComma + 1);
                if (!after.isEmpty() && after.length() <= 2) {
                    // "753,50" hoặc "1.672.381,50" → thập phân VN
                    return Double.parseDouble(t.replace(".", "").replace(",", "."));
                }
                // ',' là phân cách nghìn kiểu quốc tế "1,672,381"
                return Double.parseDouble(t.replace(",", ""));
            } else {
                // Dấu cuối là '.'  →  xét phần sau nó
                String after = t.substring(lastDot + 1);
                if (!after.isEmpty() && after.length() <= 2) {
                    long dotCount = t.chars().filter(c -> c == '.').count();
                    if (dotCount > 1) {
                        // "1.672.381" → nhiều dấu '.' = phân cách nghìn VN
                        return Double.parseDouble(t.replace(".", ""));
                    }
                    // "753.50" → thập phân quốc tế
                    return Double.parseDouble(t);
                }
                // '.' là phân cách nghìn VN "1.672.381"
                return Double.parseDouble(t.replace(".", ""));
            }
        } catch (NumberFormatException e) {
            log.warn("[VMB] parse money failed: '{}'", s);
            return 0;
        }
    }

    private static String fmt(double v, int digits) {
        java.math.BigDecimal bd = java.math.BigDecimal
                .valueOf(Math.abs(v))
                .setScale(digits, java.math.RoundingMode.HALF_UP);

        String plain = bd.toPlainString();     // "7469124"  |  "753.50"
        String[] parts = plain.split("\\.");
        String whole = parts[0];
        String frac  = parts.length > 1 ? parts[1] : "";

        // Chèn dấu '.' phân cách nghìn
        StringBuilder sb = new StringBuilder();
        int len = whole.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) sb.append('.');
            sb.append(whole.charAt(i));
        }

        String body = frac.isEmpty() ? sb.toString() : sb + "," + frac;
        return v < 0 ? "-" + body : body;
    }

    @Transactional(readOnly = true)
    public Booking findFull(Long id) {
        var b = bookingRepo.findFullById(id);
        if (b == null) throw new ToolsException("Không tìm thấy booking.");
        hydrate(b);
        return b;
    }

    /**
     * Trigger batch fetch. Cẩn thận thứ tự — đọc invoice.tickets phải sau khi
     * tickets đã được load.
     */
    private static void hydrate(Booking b) {
        b.getSegments().size();
        List<Ticket> tickets = b.getTickets();
        tickets.size();
        for (Ticket t : tickets) {
            t.getFees().size();
        }
        b.getTicketFiles().size();
        b.getBookingProofs().size();
        List<Invoice> invs = b.getInvoices();
        invs.size();
        // Trigger fetch join table cho từng invoice (batch)
        for (Invoice inv : invs) inv.getTickets().size();
    }

    // ── CREATE / UPDATE ─────────────────────────────────────────

    @Transactional
    public Booking create(SaveBookingRequest req) {
        validateCommon(req);

        long now = System.currentTimeMillis();
        Booking b = Booking.builder()
                .kind(req.getKind())
                .airlineCode(nz(req.getAirlineCode()))
                .bookingCode(nz(req.getBookingCode()))
                .routeStr(nz(req.getRouteStr()))
                .currency(req.getCurrency())
                .exchangeRate(nz(req.getExchangeRate()))
                .note(nz(req.getNote()))
                .saleDate(now)
                .sharedTicketFace(Boolean.TRUE.equals(req.getSharedTicketFace()))
                .createdAt(now)
                .updatedAt(now)
                .build();

        applySegments(b, req.getSegments());
        applyTicketsForCreate(b, req.getTickets(), now);

        Booking saved = bookingRepo.save(b);
        hydrate(saved);
        return saved;
    }

    @Transactional
    public Booking update(Long id, SaveBookingRequest req) {
        validateCommon(req);
        Booking b = bookingRepo.findFullById(id);
        if (b == null) throw new ToolsException("Không tìm thấy booking.");

        boolean oldShared = b.isSharedFace();
        boolean newShared = Boolean.TRUE.equals(req.getSharedTicketFace());

        b.setKind(req.getKind());
        b.setAirlineCode(nz(req.getAirlineCode()));
        b.setBookingCode(nz(req.getBookingCode()));
        b.setRouteStr(nz(req.getRouteStr()));
        b.setCurrency(req.getCurrency());
        b.setExchangeRate(nz(req.getExchangeRate()));
        b.setNote(nz(req.getNote()));
        b.setSharedTicketFace(newShared);
        b.setUpdatedAt(System.currentTimeMillis());

        b.getSegments().clear();
        applySegments(b, req.getSegments());

        applyTicketsForUpdate(b, req.getTickets());

        if (oldShared != newShared) cleanupFacesOnScopeChange(b, newShared);

        paymentService.recomputeStatus(b);

        hydrate(b);
        return b;
    }

    private void cleanupFacesOnScopeChange(Booking b, boolean newShared) {
        var toRemove = new java.util.ArrayList<TicketFile>();
        for (TicketFile f : b.getTicketFiles()) {
            boolean isBookingLevel = (f.getTicket() == null);
            boolean keep = newShared ? isBookingLevel : !isBookingLevel;
            if (!keep) {
                storage.delete(f.getStoredName());
                toRemove.add(f);
            }
        }
        b.getTicketFiles().removeAll(toRemove);
    }

    @Transactional
    public void delete(Long id) {
        Booking b = bookingRepo.findFullById(id);
        if (b == null) return;
        hydrate(b);

        for (TicketFile f : b.getTicketFiles()) storage.delete(f.getStoredName());
        for (BookingProof p : b.getBookingProofs()) storage.delete(p.getStoredName());
        for (Invoice inv : b.getInvoices()) deleteAllInvoiceFiles(inv);
        bookingRepo.deleteById(id);
    }

    // ── MẶT VÉ (Ticket face) ────────────────────────────────────

    @Transactional
    public TicketFile uploadBookingFace(Long bookingId, MultipartFile file) {
        Booking b = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy booking."));
        if (!b.isSharedFace()) {
            throw new ToolsException("Booking này đang ở chế độ mặt vé riêng — vui lòng bật 'mặt vé chung' trước.");
        }

        var existing = ticketFileRepo.findFirstBookingFace(bookingId).orElse(null);
        if (existing != null) {
            storage.delete(existing.getStoredName());
            ticketFileRepo.delete(existing);
        }

        var stored = storage.store(file);
        TicketFile tf = TicketFile.builder()
                .booking(b)
                .ticket(null)
                .storedName(stored.storedName())
                .originalName(stored.originalName())
                .contentType(stored.contentType())
                .sizeBytes(stored.size())
                .createdAt(System.currentTimeMillis())
                .build();
        return ticketFileRepo.save(tf);
    }

    @Transactional
    public void deleteBookingFace(Long bookingId) {
        var existing = ticketFileRepo.findFirstBookingFace(bookingId).orElse(null);
        if (existing == null) return;
        storage.delete(existing.getStoredName());
        ticketFileRepo.delete(existing);
    }

    @Transactional
    public TicketFile uploadTicketFace(Long ticketId, MultipartFile file) {
        Ticket t = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy vé."));
        Booking b = t.getBooking();
        if (b.isSharedFace()) {
            throw new ToolsException("Booking này đang ở chế độ mặt vé chung — vui lòng tắt 'mặt vé chung' trước.");
        }

        var existing = ticketFileRepo.findFirstTicketFace(ticketId).orElse(null);
        if (existing != null) {
            storage.delete(existing.getStoredName());
            ticketFileRepo.delete(existing);
        }

        var stored = storage.store(file);
        TicketFile tf = TicketFile.builder()
                .booking(b)
                .ticket(t)
                .storedName(stored.storedName())
                .originalName(stored.originalName())
                .contentType(stored.contentType())
                .sizeBytes(stored.size())
                .createdAt(System.currentTimeMillis())
                .build();
        return ticketFileRepo.save(tf);
    }

    @Transactional
    public void deleteTicketFace(Long ticketId) {
        var existing = ticketFileRepo.findFirstTicketFace(ticketId).orElse(null);
        if (existing == null) return;
        storage.delete(existing.getStoredName());
        ticketFileRepo.delete(existing);
    }

    // ── INVOICES ────────────────────────────────────────────────

    /**
     * @param ticketIds  Danh sách vé mà hóa đơn áp cho. Empty/null = chung cả booking.
     */
    @Transactional
    public Invoice createInvoice(Long bookingId, List<Long> ticketIds, String status, String note,
                                 MultipartFile draftFile,
                                 MultipartFile issuedFile,
                                 MultipartFile adjustmentFile,
                                 MultipartFile adjustmentRecordFile) {
        if (!INV_STATUS.contains(status)) throw new ToolsException("Trạng thái hóa đơn không hợp lệ.");
        Booking b = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy booking."));
        hydrate(b);

        Set<Ticket> tickets = resolveTickets(b, ticketIds);

        long now = System.currentTimeMillis();
        Invoice inv = Invoice.builder()
                .booking(b)
                .tickets(tickets)
                .status(status)
                .note(nz(note))
                .createdAt(now)
                .updatedAt(now)
                .build();

        applyInvoiceFiles(inv, status, draftFile, issuedFile, adjustmentFile, adjustmentRecordFile, true);

        return invoiceRepo.save(inv);
    }

    /**
     * Cho phép ĐỔI ticketIds khi update — user có thể chuyển hóa đơn từ "chung"
     * sang "riêng vé X" chẳng hạn. Nếu {@code newTicketIds} là null → không đổi.
     */
    @Transactional
    public Invoice updateInvoice(Long id, List<Long> newTicketIds, String newStatus, String note,
                                 MultipartFile draftFile,
                                 MultipartFile issuedFile,
                                 MultipartFile adjustmentFile,
                                 MultipartFile adjustmentRecordFile) {
        Invoice inv = invoiceRepo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy hóa đơn."));
        if (newStatus != null && !INV_STATUS.contains(newStatus))
            throw new ToolsException("Trạng thái hóa đơn không hợp lệ.");
        String target = newStatus != null ? newStatus : inv.getStatus();

        if (newTicketIds != null) {
            Booking b = inv.getBooking();
            hydrate(b);
            Set<Ticket> tickets = resolveTickets(b, newTicketIds);
            inv.getTickets().clear();
            inv.getTickets().addAll(tickets);
        }

        applyInvoiceFiles(inv, target, draftFile, issuedFile, adjustmentFile, adjustmentRecordFile, false);

        if (!"DRAFT".equals(target) && inv.getDraftFile() != null) {
            storage.delete(inv.getDraftFile());
            inv.setDraftFile(null);
            inv.setDraftOriginal(null);
        }

        if (note != null) inv.setNote(nz(note));
        inv.setStatus(target);
        inv.setUpdatedAt(System.currentTimeMillis());
        return inv;
    }

    /**
     * Resolve list<Long> ticketIds trong booking. Empty/null → empty set (chung
     * cả booking). Non-empty → validate mỗi id đều thuộc booking.
     */
    private Set<Ticket> resolveTickets(Booking b, List<Long> ticketIds) {
        Set<Ticket> tickets = new LinkedHashSet<>();
        if (ticketIds == null || ticketIds.isEmpty()) return tickets;
        Map<Long, Ticket> byId = new HashMap<>();
        for (Ticket t : b.getTickets()) byId.put(t.getId(), t);
        for (Long id : ticketIds) {
            Ticket t = byId.get(id);
            if (t == null) {
                throw new ToolsException("Vé " + id + " không thuộc booking này.");
            }
            tickets.add(t);
        }
        return tickets;
    }

    @Transactional
    public void deleteInvoice(Long id) {
        Invoice inv = invoiceRepo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy hóa đơn."));
        deleteAllInvoiceFiles(inv);
        invoiceRepo.delete(inv);
    }

    @Transactional
    public Invoice replaceInvoiceFile(Long invoiceId, String slot, MultipartFile file) {
        Invoice inv = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy hóa đơn."));
        if (file == null || file.isEmpty()) throw new ToolsException("Chưa chọn file.");
        var stored = storage.store(file);
        String oldName = null;
        switch (slot) {
            case "draft" -> {
                oldName = inv.getDraftFile();
                inv.setDraftFile(stored.storedName());
                inv.setDraftOriginal(stored.originalName());
            }
            case "issued" -> {
                oldName = inv.getIssuedFile();
                inv.setIssuedFile(stored.storedName());
                inv.setIssuedOriginal(stored.originalName());
            }
            case "adjustment" -> {
                oldName = inv.getAdjustmentFile();
                inv.setAdjustmentFile(stored.storedName());
                inv.setAdjustmentOriginal(stored.originalName());
            }
            case "record" -> {
                oldName = inv.getAdjustmentRecordFile();
                inv.setAdjustmentRecordFile(stored.storedName());
                inv.setAdjustmentRecordOriginal(stored.originalName());
            }
            default -> throw new ToolsException("Slot không hợp lệ (draft|issued|adjustment|record).");
        }
        if (oldName != null) storage.delete(oldName);
        inv.setUpdatedAt(System.currentTimeMillis());
        return inv;
    }

    @Transactional
    public Invoice deleteInvoiceFile(Long invoiceId, String slot) {
        Invoice inv = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy hóa đơn."));
        switch (slot) {
            case "draft" -> {
                if (inv.getDraftFile() != null) storage.delete(inv.getDraftFile());
                inv.setDraftFile(null);  inv.setDraftOriginal(null);
            }
            case "issued" -> {
                if (inv.getIssuedFile() != null) storage.delete(inv.getIssuedFile());
                inv.setIssuedFile(null); inv.setIssuedOriginal(null);
            }
            case "adjustment" -> {
                if (inv.getAdjustmentFile() != null) storage.delete(inv.getAdjustmentFile());
                inv.setAdjustmentFile(null); inv.setAdjustmentOriginal(null);
            }
            case "record" -> {
                if (inv.getAdjustmentRecordFile() != null) storage.delete(inv.getAdjustmentRecordFile());
                inv.setAdjustmentRecordFile(null); inv.setAdjustmentRecordOriginal(null);
            }
            default -> throw new ToolsException("Slot không hợp lệ (draft|issued|adjustment|record).");
        }
        inv.setUpdatedAt(System.currentTimeMillis());
        return inv;
    }

    // ── TICKET STATUS ───────────────────────────────────────────

    @Transactional
    public Ticket setPaidStatus(Long ticketId, String status) {
        if (!PAID.contains(status)) throw new ToolsException("Trạng thái thanh toán không hợp lệ.");
        var t = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy vé."));
        t.setPaidStatus(status);
        t.setUpdatedAt(System.currentTimeMillis());
        return t;
    }

    // ═══════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static void validateCommon(SaveBookingRequest req) {
        if (req == null) throw new ToolsException("Thiếu dữ liệu.");
        if (!KINDS.contains(req.getKind())) throw new ToolsException("Loại giao dịch không hợp lệ.");
        if (!CURRENCIES.contains(req.getCurrency())) throw new ToolsException("Đồng tiền không hợp lệ.");
        if ("USD".equals(req.getCurrency())) {
            if (req.getExchangeRate() == null || req.getExchangeRate().isBlank()) {
                throw new ToolsException("Vé USD cần nhập tỷ giá 1 USD = ? VND.");
            }
        }
        if (req.getTickets() == null || req.getTickets().isEmpty())
            throw new ToolsException("Cần ít nhất 1 vé.");
    }

    private static void applySegments(Booking b, List<SegmentIO> segs) {
        if (segs == null) return;
        int i = 0;
        for (SegmentIO s : segs) {
            if (s.getFromCode() == null || s.getFromCode().length() != 3
                    || s.getToCode()   == null || s.getToCode().length()   != 3) {
                throw new ToolsException("Mã sân bay phải 3 ký tự (IATA).");
            }
            b.addSegment(BookingSegment.builder()
                    .fromCode(s.getFromCode().toUpperCase(Locale.ROOT))
                    .toCode(s.getToCode().toUpperCase(Locale.ROOT))
                    .departLocalMs(s.getDepartLocalMs())
                    .segOrder(i++)
                    .build());
        }
    }

    private static void applyTicketsForCreate(Booking b, List<TicketIO> ins, long now) {
        for (TicketIO in : ins) b.addTicket(newTicket(in, now));
    }

    private void applyTicketsForUpdate(Booking b, List<TicketIO> ins) {
        long now = System.currentTimeMillis();
        Map<Long, Ticket> existing = new HashMap<>();
        for (Ticket t : b.getTickets()) if (t.getId() != null) existing.put(t.getId(), t);

        Set<Long> keepIds = new HashSet<>();
        List<Ticket> toAdd = new ArrayList<>();

        for (TicketIO in : ins) {
            if (in.getId() != null && existing.containsKey(in.getId())) {
                Ticket t = existing.get(in.getId());
                copyEditableFields(t, in);
                t.setUpdatedAt(now);
                keepIds.add(in.getId());
            } else {
                toAdd.add(newTicket(in, now));
            }
        }

        var ticketsToRemove = b.getTickets().stream()
                .filter(t -> t.getId() != null && !keepIds.contains(t.getId()))
                .toList();
        if (!ticketsToRemove.isEmpty()) {
            Set<Long> removedIds = new HashSet<>();
            for (Ticket t : ticketsToRemove) removedIds.add(t.getId());
            // Xóa mặt vé riêng
            var facesToRemove = b.getTicketFiles().stream()
                    .filter(f -> f.getTicket() != null && removedIds.contains(f.getTicket().getId()))
                    .toList();
            for (TicketFile f : facesToRemove) storage.delete(f.getStoredName());
            b.getTicketFiles().removeAll(facesToRemove);
            // Xóa proof file riêng
            var proofsToRemove = b.getBookingProofs().stream()
                    .filter(p -> p.getTicket() != null && removedIds.contains(p.getTicket().getId()))
                    .toList();
            for (BookingProof p : proofsToRemove) storage.delete(p.getStoredName());
            b.getBookingProofs().removeAll(proofsToRemove);
            // Xóa ticket ref trong invoices (many-to-many)
            for (Invoice inv : b.getInvoices()) {
                inv.getTickets().removeIf(t -> t.getId() != null && removedIds.contains(t.getId()));
            }
        }

        b.getTickets().removeIf(t -> t.getId() != null && !keepIds.contains(t.getId()));

        for (Ticket t : toAdd) b.addTicket(t);
    }

    private static Ticket newTicket(TicketIO in, long now) {
        Ticket t = Ticket.builder()
                .passengerName(nz(in.getPassengerName()))
                .companyId(in.getCompanyId())
                .ticketNumber(nz(in.getTicketNumber()))
                .basePrice(nz(in.getBasePrice()))
                .collectionFee(nz(in.getCollectionFee()))
                .issuanceFee(nz(in.getIssuanceFee()))
                .paidStatus(PAID.contains(in.getPaidStatus()) ? in.getPaidStatus() : "PENDING")
                .note(nz(in.getNote()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyFees(t, in.getFees(), now);
        return t;
    }

    private static void copyEditableFields(Ticket t, TicketIO in) {
        t.setPassengerName(nz(in.getPassengerName()));
        t.setCompanyId(in.getCompanyId());
        t.setTicketNumber(nz(in.getTicketNumber()));
        t.setBasePrice(nz(in.getBasePrice()));
        t.setCollectionFee(nz(in.getCollectionFee()));
        t.setIssuanceFee(nz(in.getIssuanceFee()));
        if (PAID.contains(in.getPaidStatus())) t.setPaidStatus(in.getPaidStatus());
        t.setNote(nz(in.getNote()));
        mergeFees(t, in.getFees());
    }

    /** Ghi fees mới toàn bộ cho ticket vừa tạo (chưa có id nào). */
    private static void applyFees(Ticket t, List<TicketFeeIO> ins, long now) {
        if (ins == null) return;
        int idx = 0;
        for (TicketFeeIO in : ins) {
            if (!VmbFeeType.isValid(in.getFeeType())) {
                throw new ToolsException("Loại phí không hợp lệ: " + in.getFeeType());
            }
            t.addFee(TicketFee.builder()
                    .feeType(in.getFeeType())
                    .amount(nz(in.getAmount()))
                    .note(nz(in.getNote()))
                    .orderIdx(idx++)
                    .createdAt(now)
                    .build());
        }
    }

    /**
     * Merge fees khi update: nhận diện dòng cũ theo id, dòng mới không có id
     * là add. Dòng cũ không có trong request bị xóa. Cùng feeType lặp lại đều
     * xử lý được vì phân biệt theo id.
     */
    private static void mergeFees(Ticket t, List<TicketFeeIO> ins) {
        if (ins == null) ins = List.of();
        long now = System.currentTimeMillis();

        Map<Long, TicketFee> existing = new HashMap<>();
        for (TicketFee f : t.getFees()) if (f.getId() != null) existing.put(f.getId(), f);

        Set<Long> keepIds = new HashSet<>();
        List<TicketFee> toAdd = new ArrayList<>();

        int idx = 0;
        for (TicketFeeIO in : ins) {
            if (!VmbFeeType.isValid(in.getFeeType())) {
                throw new ToolsException("Loại phí không hợp lệ: " + in.getFeeType());
            }
            if (in.getId() != null && existing.containsKey(in.getId())) {
                TicketFee f = existing.get(in.getId());
                f.setFeeType(in.getFeeType());
                f.setAmount(nz(in.getAmount()));
                f.setNote(nz(in.getNote()));
                f.setOrderIdx(idx);
                keepIds.add(in.getId());
            } else {
                toAdd.add(TicketFee.builder()
                        .feeType(in.getFeeType())
                        .amount(nz(in.getAmount()))
                        .note(nz(in.getNote()))
                        .orderIdx(idx)
                        .createdAt(now)
                        .build());
            }
            idx++;
        }

        t.getFees().removeIf(f -> f.getId() != null && !keepIds.contains(f.getId()));
        for (TicketFee f : toAdd) t.addFee(f);
    }

    private void applyInvoiceFiles(Invoice inv, String status,
                                   MultipartFile draftFile, MultipartFile issuedFile,
                                   MultipartFile adjustmentFile, MultipartFile adjustmentRecordFile,
                                   boolean isCreate) {
        if (draftFile != null && !draftFile.isEmpty()) {
            if (inv.getDraftFile() != null) storage.delete(inv.getDraftFile());
            var s = storage.store(draftFile);
            inv.setDraftFile(s.storedName());
            inv.setDraftOriginal(s.originalName());
        } else if (isCreate && "DRAFT".equals(status)) {
            throw new ToolsException("Cần chọn file hóa đơn nháp.");
        }

        if (issuedFile != null && !issuedFile.isEmpty()) {
            if (inv.getIssuedFile() != null) storage.delete(inv.getIssuedFile());
            var s = storage.store(issuedFile);
            inv.setIssuedFile(s.storedName());
            inv.setIssuedOriginal(s.originalName());
        } else if (isCreate && "ISSUED".equals(status)) {
            throw new ToolsException("Cần chọn file hóa đơn đã phát hành.");
        } else if (isCreate && "ADJUSTED".equals(status)) {
            throw new ToolsException("Cần chọn file hóa đơn đã phát hành trước khi điều chỉnh.");
        }

        if (adjustmentFile != null && !adjustmentFile.isEmpty()) {
            if (inv.getAdjustmentFile() != null) storage.delete(inv.getAdjustmentFile());
            var s = storage.store(adjustmentFile);
            inv.setAdjustmentFile(s.storedName());
            inv.setAdjustmentOriginal(s.originalName());
        } else if (isCreate && "ADJUSTED".equals(status)) {
            throw new ToolsException("Cần chọn file hóa đơn điều chỉnh.");
        }

        if (adjustmentRecordFile != null && !adjustmentRecordFile.isEmpty()) {
            if (inv.getAdjustmentRecordFile() != null) storage.delete(inv.getAdjustmentRecordFile());
            var s = storage.store(adjustmentRecordFile);
            inv.setAdjustmentRecordFile(s.storedName());
            inv.setAdjustmentRecordOriginal(s.originalName());
        } else if (isCreate && "ADJUSTED".equals(status)) {
            throw new ToolsException("Cần chọn file biên bản điều chỉnh đã ký.");
        }
    }

    private void deleteAllInvoiceFiles(Invoice inv) {
        storage.delete(inv.getDraftFile());
        storage.delete(inv.getIssuedFile());
        storage.delete(inv.getAdjustmentFile());
        storage.delete(inv.getAdjustmentRecordFile());
    }

    // ── ENRICH: Bơm companyName / companyShortName vào TicketIO ──────────

    @Transactional(readOnly = true)
    public void enrichCompanies(java.util.List<BookingIO> bookings) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (BookingIO b : bookings) {
            if (b.getTickets() == null) continue;
            for (TicketIO t : b.getTickets()) {
                if (t.getCompanyId() != null) ids.add(t.getCompanyId());
            }
        }
        if (ids.isEmpty()) return;

        var map = new java.util.HashMap<Long, com.nhatnam.server.tools.vmb.entity.Company>();
        for (var c : companyRepo.findAllById(ids)) map.put(c.getId(), c);

        for (BookingIO b : bookings) {
            if (b.getTickets() == null) continue;
            for (TicketIO t : b.getTickets()) {
                if (t.getCompanyId() == null) continue;
                var c = map.get(t.getCompanyId());
                if (c == null) continue;
                t.setCompanyName(c.getName());
                t.setCompanyShortName(c.getShortName());
            }
        }
    }
}