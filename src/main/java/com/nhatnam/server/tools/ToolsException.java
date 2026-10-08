package com.nhatnam.server.tools;

/**
 * Lỗi nghiệp vụ / cấu hình của nhóm tiện ích.
 *
 * Mang HAI thông điệp tách biệt:
 *   • message — ngắn gọn, dành cho người dùng cuối trên giao diện.
 *   • detail  — đầy đủ, dành cho log của quản trị hệ thống.
 *
 * Lý do tách: chi tiết kỹ thuật (đường dẫn driver, tên hệ điều hành, mã lỗi
 * PKCS#11) chỉ có ý nghĩa với người vận hành. Đổ nguyên chuỗi đó lên màn hình
 * kế toán vừa khó hiểu vừa lộ cấu hình máy chủ ra ngoài — mà trang ký số lại
 * không yêu cầu đăng nhập.
 */
public class ToolsException extends RuntimeException {

    private final String detail;

    /** Khi thông điệp cho người dùng và cho log là một */
    public ToolsException(String message) {
        this(message, message, null);
    }

    public ToolsException(String message, String detail) {
        this(message, detail, null);
    }

    public ToolsException(String message, String detail, Throwable cause) {
        super(message, cause);
        this.detail = detail != null ? detail : message;
    }

    /** Nội dung đầy đủ để ghi log — KHÔNG trả về giao diện */
    public String getDetail() {
        return detail;
    }
}