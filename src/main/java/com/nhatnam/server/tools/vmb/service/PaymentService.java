package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.entity.Booking;
import com.nhatnam.server.tools.vmb.entity.BookingPayment;
import com.nhatnam.server.tools.vmb.entity.Ticket;
import com.nhatnam.server.tools.vmb.entity.TicketFee;
import com.nhatnam.server.tools.vmb.repository.BookingPaymentRepository;
import com.nhatnam.server.tools.vmb.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

/**
 * Ghi nhận thanh toán 1 booking hoặc thu batch nhiều booking.
 *
 * ── Trạng thái ─────────────────────────────────────────
 * Sau mỗi lần thu, tính lại {@code paidAmount} và {@code paymentStatus} cho
 * booking:
 *   Σ(payments) = 0             → UNPAID
 *   0 &lt; Σ &lt; grandTotal       → PARTIAL
 *   Σ &gt;= grandTotal            → PAID
 * Cho phép thu THÊM sau khi PAID (khách trả bù) nhưng không đổi trạng thái.
 *
 * ── Thu batch ─────────────────────────────────────────
 * User chọn N booking + upload 1 ảnh. Tạo N record cùng {@code storedName}
 * nhưng khác {@code bookingId}. Batch BẮT BUỘC thu ĐỦ mỗi booking — không
 * cho phép thu 1 phần khi làm hàng loạt (dễ nhầm số).
 *
 * ── 2026-09-19 refactor ───────────────────────────────
 * grandTotal() trước đây cộng {@code t.getServiceFee()}. Cột đó đã bị xóa,
 * thay bằng list {@link TicketFee}. Nay cộng tổng của {@code t.getFees()}
 * — cùng công thức tính totals ở BookingService.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PaymentService {

    private final BookingRepository bookingRepo;
    private final BookingPaymentRepository paymentRepo;
    private final VmbStorageService storage;

    // ── Ghi 1 lần thu cho 1 booking ────────────────────

    @Transactional
    public PaymentIO recordPayment(Long bookingId, String amount, String note,
                                   MultipartFile file, String username) {
        Booking b = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy booking."));

        double amt = parse(amount);
        if (!(amt > 0)) throw new ToolsException("Số tiền thu phải > 0.");

        long now = System.currentTimeMillis();
        BookingPayment p = BookingPayment.builder()
                .booking(b)
                .amount(amount.trim())
                .note(note == null ? null : note.trim())
                .createdAt(now)
                .createdBy(username)
                .build();

        if (file != null && !file.isEmpty()) {
            var stored = storage.store(file);
            p.setStoredName(stored.storedName());
            p.setOriginalName(stored.originalName());
            p.setContentType(stored.contentType());
            p.setSizeBytes(stored.size());
        }
        paymentRepo.save(p);

        recomputeStatus(b);
        bookingRepo.save(b);

        return view(p);
    }

    // ── Thu batch nhiều booking, cùng 1 ảnh ────────────

    @Transactional
    public List<PaymentIO> batchPay(List<Long> bookingIds, String note,
                                    MultipartFile file, String username) {
        if (bookingIds == null || bookingIds.isEmpty())
            throw new ToolsException("Chưa chọn booking nào.");

        List<Booking> bookings = bookingRepo.findAllById(bookingIds);
        if (bookings.size() != bookingIds.size())
            throw new ToolsException("Có booking không tồn tại (đã bị xóa?).");

        long now = System.currentTimeMillis();

        // Upload file 1 lần, dùng chung storedName cho mọi record
        String storedName = null, originalName = null, contentType = null;
        Long sizeBytes = null;
        if (file != null && !file.isEmpty()) {
            var stored = storage.store(file);
            storedName   = stored.storedName();
            originalName = stored.originalName();
            contentType  = stored.contentType();
            sizeBytes    = stored.size();
        }

        var out = new java.util.ArrayList<PaymentIO>();
        for (Booking b : bookings) {
            double remain = grandTotal(b) - parseNz(b.getPaidAmount());
            if (remain <= 0) {
                // Đã PAID → bỏ qua, không tạo record thừa
                continue;
            }
            // Batch = phải thu ĐỦ số còn lại của booking đó
            String amtStr = fmtVnLike(remain, b.getCurrency());
            BookingPayment p = BookingPayment.builder()
                    .booking(b)
                    .amount(amtStr)
                    .note(note == null ? "Thu batch" : note.trim())
                    .storedName(storedName)
                    .originalName(originalName)
                    .contentType(contentType)
                    .sizeBytes(sizeBytes)
                    .createdAt(now)
                    .createdBy(username)
                    .build();
            paymentRepo.save(p);

            recomputeStatus(b);
            bookingRepo.save(b);
            out.add(view(p));
        }
        return out;
    }

    // ── Xóa 1 payment ──────────────────────────────────

    @Transactional
    public void deletePayment(Long paymentId) {
        BookingPayment p = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy phiếu thu."));
        Booking b = p.getBooking();

        // Nếu file này còn record khác dùng (batch), giữ lại
        String storedName = p.getStoredName();
        paymentRepo.delete(p);

        if (storedName != null && paymentRepo.countByStoredName(storedName) == 0) {
            storage.delete(storedName);
        }
        recomputeStatus(b);
        bookingRepo.save(b);
    }

    // ── List payments của 1 booking ────────────────────

    @Transactional(readOnly = true)
    public List<PaymentIO> listByBooking(Long bookingId) {
        return paymentRepo.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(PaymentService::view).toList();
    }

    // ── Recompute helpers ──────────────────────────────

    /**
     * Tính lại paidAmount + paymentStatus cho booking. Public để service
     * khác (BookingService khi update giá) có thể gọi.
     */
    public void recomputeStatus(Booking b) {
        double sum = 0;
        for (BookingPayment p : paymentRepo.findByBookingIdOrderByCreatedAtAsc(b.getId())) {
            sum += parseNz(p.getAmount());
        }
        b.setPaidAmount(fmtVnLike(sum, b.getCurrency()));

        double total = grandTotal(b);
        String status;
        if (sum <= 0)             status = "UNPAID";
        else if (sum + 0.5 < total) status = "PARTIAL";  // tolerance làm tròn 0.5
        else                       status = "PAID";
        b.setPaymentStatus(status);
        b.setUpdatedAt(System.currentTimeMillis());
    }

    /**
     * ── 2026-09-19 refactor ─────────────────────────────
     * grandTotal = Σ(basePrice + collectionFee + Σfees + issuanceFee) qua
     * mọi ticket. Thay {@code t.getServiceFee()} (cột cũ đã bỏ) bằng tổng
     * của {@link TicketFee}.
     *
     * Vì gọi trong {@link #recomputeStatus} sau khi save booking (mà booking
     * có thể mới, ticket lazy chưa init), duyệt Ticket ở đây có thể trigger
     * fetch — ổn vì {@code recomputeStatus} luôn chạy trong transaction.
     */
    private static double grandTotal(Booking b) {
        double s = 0;
        for (Ticket t : b.getTickets()) {
            s += parseNz(t.getBasePrice())
                    + parseNz(t.getCollectionFee())
                    + sumFees(t.getFees())
                    + parseNz(t.getIssuanceFee());
        }
        return s;
    }

    private static double sumFees(List<TicketFee> fees) {
        if (fees == null) return 0;
        double s = 0;
        for (TicketFee f : fees) s += parseNz(f.getAmount());
        return s;
    }

    private static double parse(String s) {
        double n = parseNz(s);
        if (Double.isNaN(n)) throw new ToolsException("Số tiền không hợp lệ: " + s);
        return n;
    }

    /** Parse String tiền, trả 0 nếu null/empty (không throw). Đồng bộ với money.js. */
    private static double parseNz(String s) {
        if (s == null || s.isBlank()) return 0;
        String t = s.trim();
        int lastComma = t.lastIndexOf(',');
        int lastDot   = t.lastIndexOf('.');
        String normalized;
        if (lastComma >= 0) {
            if (lastComma > lastDot) normalized = t.replace(".", "").replace(",", ".");
            else                     normalized = t.replace(",", "");
        } else if (lastDot >= 0) {
            int dotCount = t.length() - t.replace(".", "").length();
            int afterDot = t.length() - lastDot - 1;
            if (dotCount > 1 || (afterDot == 3 && lastDot > 0)) normalized = t.replace(".", "");
            else                                                normalized = t;
        } else {
            normalized = t;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String fmtVnLike(double v, String currency) {
        int digits = "USD".equals(currency) ? 2 : 0;
        String abs = String.format(Locale.US, "%." + digits + "f", Math.abs(v));
        String[] parts = abs.split("\\.");
        String whole = parts[0].replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".");
        String body = parts.length > 1 ? whole + "," + parts[1] : whole;
        return v < 0 ? "-" + body : body;
    }

    /**
     * ── FIX 2026-09-15 ─────────────────────────────────
     * Đổi prefix "/uploads/" → "/vmb-files/". File biên nhận thu tiền được lưu
     * qua VmbStorageService — cùng thư mục vật lý với ticket face + giấy tờ
     * CCCD/PP. Chỉ có endpoint /vmb-files/** được cấu hình public + có
     * ResourceHandler (xem VmbFileWebConfig). Prefix cũ /uploads/ không có
     * ResourceHandler nào map ra file → click preview bị JwtAuthenticationFilter
     * chặn về "/login" (redirect "/").
     */
    public static PaymentIO view(BookingPayment p) {
        return PaymentIO.builder()
                .id(p.getId())
                .bookingId(p.getBooking().getId())
                .amount(p.getAmount())
                .note(p.getNote())
                .fileUrl(p.getStoredName() == null ? null : ("/vmb-files/" + p.getStoredName()))
                .fileOriginal(p.getOriginalName())
                .createdAt(p.getCreatedAt())
                .createdBy(p.getCreatedBy())
                .build();
    }
}