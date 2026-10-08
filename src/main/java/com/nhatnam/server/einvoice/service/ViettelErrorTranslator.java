package com.nhatnam.server.einvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.einvoice.EInvoiceException;
import com.nhatnam.server.einvoice.dto.ViettelInvoiceDto.GeneralInvoiceInfo;
import lombok.extern.log4j.Log4j2;

/**
 * Dịch lỗi thô của Viettel sang thông báo tiếng Việt cho người dùng cuối.
 *
 * Viettel trả lỗi dưới nhiều dạng khác nhau tùy endpoint:
 *   • HTTP 400 + body {"code":400,"message":"INVOICE_SERIAL_NOT_FOUND","data":"..."}
 *   • HTTP 200 + body {"errorCode":"...","description":"..."}
 *
 * Danh sách mã lỗi của Viettel không được công bố đầy đủ, nên ở đây nhận diện theo
 * TỪ KHÓA trong mã lỗi + mô tả thay vì so khớp tuyệt đối. Gặp mã lạ thì giữ
 * nguyên mô tả gốc của Viettel (kind = UNKNOWN) để không nuốt mất thông tin.
 */
@Log4j2
public final class ViettelErrorTranslator {

    private ViettelErrorTranslator() {}

    /**
     * @param body    body phản hồi (JSON hoặc text) của Viettel
     * @param info    thông tin mẫu số/ký hiệu đã gửi — để ghi rõ trong thông báo
     * @param status  HTTP status, 0 nếu không có
     */
    public static EInvoiceException translate(String body, GeneralInvoiceInfo info, int status,
                                              ObjectMapper mapper) {
        String code = "", message = "", data = "";

        if (body != null && !body.isBlank()) {
            try {
                JsonNode n = mapper.readTree(body);
                code    = text(n, "errorCode", "code");
                message = text(n, "message", "description");
                data    = text(n, "data", "desc");
            } catch (Exception ignore) {
                message = body;
            }
        }

        String haystack = (code + " " + message + " " + data).toUpperCase();
        String series   = info != null ? nz(info.getInvoiceSeries())  : "";
        String template = info != null ? nz(info.getTemplateCode())   : "";
        String suffix   = (!series.isBlank() || !template.isBlank())
                ? String.format(" (ký hiệu %s, mẫu số %s)", dash(series), dash(template))
                : "";
        String raw = "HTTP " + status + " | " + trimTo(body, 500);

        // ── Ký hiệu / mẫu số chưa tồn tại ────────────────────────────
        if (contains(haystack, "SERIAL_NOT_FOUND", "SERIAL NOT FOUND", "TEMPLATE_NOT_FOUND")
                || (haystack.contains("SERIAL") && haystack.contains("NOT_FOUND"))
                || haystack.contains("KÝ HIỆU HÓA ĐƠN KHÔNG TỒN TẠI")) {
            return new EInvoiceException(EInvoiceException.Kind.SERIAL_NOT_FOUND,
                    "Dải hóa đơn chưa được tạo" + suffix
                            + ". Cần đăng ký ký hiệu này với cơ quan thuế và khai báo trên hệ thống Viettel trước khi phát hành.",
                    raw);
        }

        // ── Hết số trong dải ─────────────────────────────────────────
        if (contains(haystack, "OUT_OF_INVOICE", "OUT_OF_RANGE", "OUT OF RANGE",
                "NOT_ENOUGH", "EXHAUST", "USED_UP", "MAX_INVOICE", "NO_MORE_INVOICE",
                "HẾT SỐ", "ĐÃ SỬ DỤNG HẾT")) {
            return new EInvoiceException(EInvoiceException.Kind.SERIAL_EXHAUSTED,
                    "Dải hóa đơn đã hết" + suffix
                            + ". Cần đăng ký thêm dải mới với cơ quan thuế rồi cập nhật lại cấu hình ký hiệu.",
                    raw);
        }

        // ── Sai tài khoản ────────────────────────────────────────────
        if (status == 401 || status == 403
                || contains(haystack, "UNAUTHORIZED", "INVALID_CREDENTIAL", "ACCESS_DENIED")) {
            return new EInvoiceException(EInvoiceException.Kind.AUTH_FAILED,
                    "Không đăng nhập được hệ thống hóa đơn Viettel. Kiểm tra lại tài khoản/mật khẩu trong cấu hình.",
                    raw);
        }

        // ── Viettel lỗi / không gọi được ─────────────────────────────
        if (status >= 500) {
            return new EInvoiceException(EInvoiceException.Kind.UPSTREAM_UNAVAILABLE,
                    "Hệ thống hóa đơn Viettel đang lỗi, vui lòng thử lại sau.", raw);
        }

        // ── Không nhận diện được → giữ nguyên mô tả gốc ──────────────
        String detail = !data.isBlank() ? data : (!message.isBlank() ? message : "lỗi không xác định");
        return new EInvoiceException(EInvoiceException.Kind.UNKNOWN,
                "Viettel từ chối yêu cầu: " + detail, raw);
    }

    /** Dùng cho trường hợp không có HTTP response (timeout, DNS, ...) */
    public static EInvoiceException unreachable(Throwable cause) {
        return new EInvoiceException(EInvoiceException.Kind.UPSTREAM_UNAVAILABLE,
                "Không kết nối được tới hệ thống hóa đơn Viettel. Kiểm tra mạng và thử lại.",
                cause != null ? cause.toString() : null);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static String text(JsonNode n, String... fields) {
        for (String f : fields) {
            JsonNode v = n.get(f);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return "";
    }

    private static boolean contains(String haystack, String... needles) {
        for (String s : needles) if (haystack.contains(s)) return true;
        return false;
    }

    private static String nz(String s)   { return s == null ? "" : s; }
    private static String dash(String s) { return s == null || s.isBlank() ? "—" : s; }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
