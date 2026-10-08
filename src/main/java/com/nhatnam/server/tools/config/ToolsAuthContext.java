package com.nhatnam.server.tools.config;

import com.nhatnam.server.tools.ToolsException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Nơi duy nhất controller đọc username/quyền của người đang gọi.
 *
 * Tách hàm ra thay vì viết "request.getAttribute(...)" khắp nơi giúp:
 *   1. Tránh gõ nhầm tên attribute.
 *   2. Chỗ nào quên inject HttpServletRequest, IDE sẽ báo ngay khi đọc.
 *   3. Ném ToolsException với message tiếng Việt nếu attribute thiếu — nghĩa
 *      là ai đó đã lỡ thêm endpoint mới quên khai vào ToolsAuthFilter.
 */
public final class ToolsAuthContext {

    private ToolsAuthContext() {}

    /** Bắt buộc phải có — nếu null nghĩa là filter chưa chạy → lỗi cấu hình. */
    public static String username(HttpServletRequest request) {
        Object v = request.getAttribute(ToolsAuthFilter.ATTR_USERNAME);
        if (v == null) {
            throw new ToolsException(
                    "Chưa đăng nhập.",
                    "Endpoint " + request.getRequestURI() + " chưa được đưa vào ToolsAuthFilter"
            );
        }
        return String.valueOf(v);
    }

    /**
     * Không ném lỗi nếu thiếu — dùng cho endpoint PUBLIC muốn biết ai đang gọi
     * NẾU có token, nhưng vẫn phục vụ cả khi anonymous.
     *
     * Ví dụ: /api/tools/vmb/lookup/{id}/reveal — nếu user đăng nhập tools thì
     * dùng TOTP secret riêng, nếu không thì fallback yml secret.
     */
    public static String usernameOrNull(HttpServletRequest request) {
        Object v = request.getAttribute(ToolsAuthFilter.ATTR_USERNAME);
        return v == null ? null : String.valueOf(v);
    }

    /** Trả về false nếu attribute thiếu (thay vì ném lỗi) — dùng cho check "nếu admin thì...". */
    public static boolean isAdmin(HttpServletRequest request) {
        Object v = request.getAttribute(ToolsAuthFilter.ATTR_ADMIN);
        return v instanceof Boolean b && b;
    }

    /** Ngắn gọn cho controller nào chỉ nhận yêu cầu của admin */
    public static void requireAdmin(HttpServletRequest request) {
        if (!isAdmin(request)) {
            throw new ToolsException("Chỉ quản trị viên mới được thực hiện thao tác này.");
        }
    }
}
