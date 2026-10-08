package com.nhatnam.server.einvoice;

/**
 * Lỗi nghiệp vụ khi làm việc với Viettel e-invoice.
 *
 * Mục đích: tách "lỗi người dùng cần biết" khỏi "lỗi hệ thống cần stack trace".
 * Controller bắt exception này thì chỉ log WARN 1 dòng và trả message tiếng Việt
 * cho UI — không đổ stack trace ra log nữa.
 */
public class EInvoiceException extends RuntimeException {

    public enum Kind {
        /** Ký hiệu/mẫu số chưa được tạo hoặc chưa đăng ký với CQT */
        SERIAL_NOT_FOUND,
        /** Dải hóa đơn đã dùng hết số */
        SERIAL_EXHAUSTED,
        /** Sai tài khoản / hết hạn đăng nhập Viettel */
        AUTH_FAILED,
        /** Không gọi được Viettel (mạng, timeout, 5xx) */
        UPSTREAM_UNAVAILABLE,
        /** Lỗi khác — giữ nguyên mô tả gốc của Viettel */
        UNKNOWN
    }

    private final Kind   kind;
    /** Nguyên văn phản hồi Viettel — chỉ dùng để log, không trả về UI */
    private final String rawDetail;

    public EInvoiceException(Kind kind, String message, String rawDetail) {
        super(message);
        this.kind      = kind;
        this.rawDetail = rawDetail;
    }

    public Kind getKind()        { return kind; }
    public String getRawDetail() { return rawDetail; }

    /** true = lỗi cấu hình/nghiệp vụ, log WARN gọn; false = nên log kèm stack trace */
    public boolean isBusinessError() {
        return kind != Kind.UNKNOWN && kind != Kind.UPSTREAM_UNAVAILABLE;
    }
}
