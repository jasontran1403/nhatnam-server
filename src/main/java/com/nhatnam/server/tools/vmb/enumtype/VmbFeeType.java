package com.nhatnam.server.tools.vmb.enumtype;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 10 loại phí có thể phát sinh trên 1 vé. 1 vé có thể có nhiều phí, và mỗi
 * loại phí cũng có thể xuất hiện nhiều lần (VD: 2 lần Phí hành lý cho 2 kiện).
 *
 * KHÔNG dùng {@code enum} Java để lưu vào DB vì:
 *   - Nếu sau này bạn cần thêm/bớt loại phí (ví dụ thêm "Phí ưu tiên"), thêm
 *     enum phải build lại BE + migrate — data cũ giá trị enum không còn hợp
 *     lệ sẽ crash. Dùng String thoải mái hơn.
 *   - Java enum khai báo cứng nhắc, khó thêm label i18n về sau.
 *
 * Dùng lớp constants tĩnh + Map giá trị → label VN để:
 *   - Validate ở service (contains).
 *   - Trả về endpoint {@code GET /api/tools/vmb/fee-types} cho FE dropdown.
 */
public final class VmbFeeType {
    private VmbFeeType() {}

    public static final String CHANGE_TICKET         = "CHANGE_TICKET";
    public static final String REFUND_TICKET         = "REFUND_TICKET";
    public static final String BAGGAGE               = "BAGGAGE";
    public static final String SEAT                  = "SEAT";
    public static final String MEAL                  = "MEAL";
    public static final String FAST_TRACK            = "FAST_TRACK";
    public static final String INSURANCE             = "INSURANCE";
    public static final String SPECIAL_ASSISTANCE    = "SPECIAL_ASSISTANCE";
    public static final String UNACCOMPANIED_MINOR   = "UNACCOMPANIED_MINOR";
    public static final String PET                   = "PET";
    public static final String PRICE_UPGRADE         = "PRICE_UPGRADE";

    /**
     * LinkedHashMap giữ thứ tự chèn — thứ tự hiển thị dropdown khớp với thứ tự
     * user đã nêu trong yêu cầu.
     */
    public static final Map<String, String> LABELS_VI = new LinkedHashMap<>();
    static {
        LABELS_VI.put(CHANGE_TICKET,       "Phí đổi vé");
        LABELS_VI.put(REFUND_TICKET,       "Phí hoàn vé");
        LABELS_VI.put(BAGGAGE,             "Phí hành lý");
        LABELS_VI.put(SEAT,                "Phí chỗ ngồi");
        LABELS_VI.put(MEAL,                "Phí suất ăn");
        LABELS_VI.put(FAST_TRACK,          "Phí fast track");
        LABELS_VI.put(INSURANCE,           "Phí bảo hiểm");
        LABELS_VI.put(SPECIAL_ASSISTANCE,  "Phí trợ giúp đặc biệt");
        LABELS_VI.put(UNACCOMPANIED_MINOR, "Phí trẻ em đi một mình");
        LABELS_VI.put(PET,                 "Phí mang theo thú cưng");
        LABELS_VI.put(PRICE_UPGRADE,       "Phí nâng giá thường");
    }

    public static boolean isValid(String code) {
        return code != null && LABELS_VI.containsKey(code);
    }

    public static String labelOf(String code) {
        return LABELS_VI.getOrDefault(code, code);
    }
}