package com.nhatnam.server.einvoice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO layer cho Viettel VinInvoice API.
 * Tất cả field tên theo đúng JSON trong Postman collection.
 */
public class ViettelInvoiceDto {

    // ═══════════════════════════════════════════════════════════════
    // REQUEST
    // ═══════════════════════════════════════════════════════════════

    /**
     * Body chính gửi lên API tạo hóa đơn:
     * POST /InvoiceAPI/InvoiceWS/createInvoice/{supplierTaxCode}       — phát hành ngay (HSM)
     * POST /InvoiceAPI/InvoiceWS/createOrUpdateInvoiceDraft/{mst}      — tạo nháp
     */
    @Data
    @Builder
    public static class CreateInvoiceRequest {
        private GeneralInvoiceInfo generalInvoiceInfo;
        private BuyerInfo buyerInfo;
        private List<PaymentMethod> payments;
        private List<ItemInfo> itemInfo;
        private List<TaxBreakdown> taxBreakdowns;
        private SummarizeInfo summarizeInfo;
        private List<Metadata> metadata;
    }

    /**
     * Thông tin chung của hóa đơn.
     * adjustmentType:
     *   "1" = hóa đơn gốc
     *   "3" = hóa đơn thay thế
     *   "5" = hóa đơn điều chỉnh
     */
    @Data
    @Builder
    public static class GeneralInvoiceInfo {
        private String invoiceType;             // "1" = hóa đơn GTGT
        private String templateCode;            // "1/770"
        private String invoiceSeries;           // "K23TXM"
        private String currencyCode;            // "VND"
        private Integer exchangeRate;           // 1
        private String adjustmentType;          // "1" gốc / "3" thay thế / "5" điều chỉnh
        private String adjustmentInvoiceType;   // chỉ khi adjustmentType=5: "1"=tăng, "2"=giảm
        private Boolean paymentStatus;          // true = đã thanh toán
        private Boolean cusGetInvoiceRight;     // true = khách có quyền lấy HĐ

        private Long invoiceIssuedDate;         // null = Viettel tự lấy now()
        private String transactionUuid;         // null = bỏ qua idempotency check

        // USB Token fields
        private String certificateSerial;       // Serial cert trên USB token (GetHash step)
        private String reservationCode;         // Mã nháp từ GetHash → truyền vào InsertSignature
        private Integer validation;             // 0 = không validate strict

        // Chỉ dùng khi HĐ điều chỉnh / thay thế
        private String originalInvoiceId;
        private Long originalInvoiceIssueDate;
        private String additionalReferenceDesc;
        private Long additionalReferenceDate;
        private String invoiceNote;
    }

    /**
     * Thông tin người mua.
     *
     * Khách lẻ KHÔNG lấy hóa đơn:  buyerNotGetInvoice = "1"
     * Khách có MST (lấy HĐ):        buyerNotGetInvoice = "0", điền buyerTaxCode
     */
    @Data
    @Builder
    public static class BuyerInfo {
        private String buyerName;           // Tên người mua
        private String buyerLegalName;      // Tên pháp lý (công ty)
        private String buyerTaxCode;        // MST người mua (null nếu khách lẻ)
        private String buyerAddressLine;    // Địa chỉ
        private String buyerPhoneNumber;
        private String buyerEmail;
        private String buyerIdNo;           // Số CCCD/CMND (khách lẻ cá nhân)
        private Integer buyerIdType;        // 1=CMND, 2=CCCD, 3=Hộ chiếu
        /**
         * "0" = khách CÓ lấy hóa đơn (có MST hoặc cá nhân)
         * "1" = khách KHÔNG lấy hóa đơn (mua lẻ, vô danh)
         */
        private String buyerNotGetInvoice;
    }

    /** Phương thức thanh toán */
    @Data
    @Builder
    public static class PaymentMethod {
        /**
         * paymentMethod: "1"=TM, "2"=CK, "3"=TM/CK
         * (có thể null nếu chỉ truyền paymentMethodName)
         */
        private String paymentMethod;
        private String paymentMethodName;   // "Tiền mặt", "CK", "TM/CK"
    }

    /**
     * Dòng hàng hóa / dịch vụ.
     *
     * selection:
     *   1 = hàng hóa thường
     *   2 = ghi chú trong bảng (không có giá)
     *   3 = chiết khấu tổng hóa đơn
     *
     * taxPercentage:
     *   0, 5, 8, 10 = VAT thực
     *  -1 = không xác định
     *  -2 = không chịu thuế / không tính thuế
     */
    @Data
    @Builder
    public static class ItemInfo {
        private Integer lineNumber;
        private Integer selection;                          // 1=hàng, 2=ghi chú, 3=CK tổng
        private String itemCode;
        private String itemName;
        private String unitName;                            // "cái", "kg", "phần", ...
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal itemTotalAmountWithoutTax;
        private BigDecimal itemTotalAmountAfterDiscount;
        private BigDecimal itemTotalAmountWithTax;
        private Integer taxPercentage;                      // xem giải thích trên
        private BigDecimal taxAmount;
        private BigDecimal discount;                        // % chiết khấu dòng
        private BigDecimal itemDiscount;                    // tiền chiết khấu dòng
        private String itemNote;
        /**
         * isIncreaseItem:
         *   null / true  = hàng tăng (HĐ thay thế / điều chỉnh tăng)
         *   false        = hàng giảm (chiết khấu tổng hoặc điều chỉnh giảm)
         */
        private Boolean isIncreaseItem;
    }

    /** Breakdown thuế theo nhóm thuế suất */
    @Data
    @Builder
    public static class TaxBreakdown {
        private Integer taxPercentage;
        private BigDecimal taxableAmount;
        private BigDecimal taxAmount;
    }

    /** Tổng hợp tiền hóa đơn */
    @Data
    @Builder
    public static class SummarizeInfo {
        private BigDecimal sumOfTotalLineAmountWithoutTax;
        private BigDecimal totalAmountAfterDiscount;
        private BigDecimal totalAmountWithoutTax;
        private BigDecimal totalTaxAmount;
        private BigDecimal totalAmountWithTax;
        private String totalAmountWithTaxInWords;           // null = Viettel tự điền
        private BigDecimal discountAmount;
    }

    /** Trường metadata (vd: ghi chú) */
    @Data
    @Builder
    public static class Metadata {
        private String keyTag;       // "invoiceNote"
        private String stringValue;
        private String valueType;    // "text"
        private String keyLabel;     // "Ghi chú"
    }

    // ───────────────────────────────────────────────────────────────
    // Request lấy file hóa đơn (7.3)
    // ───────────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class GetFileRequest {
        private String supplierTaxCode;
        private String invoiceNo;
        private String templateCode;
        /** "PDF" hoặc "XML" */
        private String fileType;
    }

    // ═══════════════════════════════════════════════════════════════
    // RESPONSE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Response tạo hóa đơn (createInvoice / createOrUpdateInvoiceDraft).
     * errorCode = "200" hoặc null = thành công.
     */
    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateInvoiceResponse {
        private String errorCode;       // null = OK
        private String description;     // mô tả lỗi nếu có

        // Viettel trả invoiceNo trong nested "result" object
        private InvoiceResult result;

        // Fallback: một số endpoint trả flat (backward compat)
        private String invoiceNo;
        private String transactionUuid;
        private Long   invoiceIssuedDate;
        private String invoiceType;
        private String templateCode;
        private String invoiceSeries;

        /** Lấy invoiceNo từ result hoặc flat field */
        public String getInvoiceNo() {
            return result != null && result.getInvoiceNo() != null
                    ? result.getInvoiceNo() : invoiceNo;
        }

        @Data
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class InvoiceResult {
            private String supplierTaxCode;
            private String invoiceNo;
            private String transactionID;
            private String reservationCode;
            private String codeOfTax;
        }
    }

    /**
     * Response của createInvoiceUsbTokenGetHash.
     * Chứa hash cần ký bằng USB token.
     */
    @Data
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class GetHashResponse {
        private String errorCode;
        private String description;
        private GetHashResult result;

        public boolean isSuccess() {
            return errorCode == null || errorCode.isBlank() || "200".equals(errorCode);
        }

        @Data
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public static class GetHashResult {
            private String hashData;            // chuỗi hash cần ký
            private String reservationCode;     // mã nháp, truyền vào InsertSignature
            private String transactionID;
        }
    }

    /**
     * Response lấy file hóa đơn.
     * fileToBytes = base64-encoded PDF hoặc XML.
     */
    @Data
    public static class GetFileResponse {
        private String errorCode;
        private String description;
        private String fileToBytes;     // Base64 string
        private String fileName;
    }
}