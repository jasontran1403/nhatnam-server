package com.nhatnam.server.einvoice.dto;

import lombok.Data;

/**
 * DTO do POS/client gửi lên server để tạo hóa đơn điện tử.
 * Server sẽ dùng orderId load PosOrder từ DB rồi gọi Viettel API.
 */
public class EInvoiceRequestDto {

    /** Loại hóa đơn cần tạo */
    public enum InvoiceMode {
        /** Bước 2.1: Khách lẻ không lấy hóa đơn */
        RETAIL,
        /** Bước 2.2: Khách có MST, lấy hóa đơn */
        BUSINESS
    }

    /**
     * Request tạo hóa đơn điện tử cho 1 đơn POS.
     */
    @Data
    public static class CreateRequest {
        /** ID đơn hàng POS */
        private Long orderId;

        /** RETAIL = khách lẻ, BUSINESS = có MST */
        private InvoiceMode mode;

        /**
         * Chỉ bắt buộc khi mode = BUSINESS.
         * Thông tin người mua có MST.
         */
        private BusinessBuyerInfo businessBuyer;
    }

    /**
     * Thông tin người mua doanh nghiệp (mode = BUSINESS).
     * Nhân viên POS nhập vào trước khi tạo hóa đơn.
     */
    @Data
    public static class BusinessBuyerInfo {
        /** MST người mua — bắt buộc */
        private String taxCode;

        /** Tên công ty / tên pháp lý */
        private String companyName;

        /** Địa chỉ công ty */
        private String address;

        /** Email nhận hóa đơn */
        private String email;

        /** SĐT (không bắt buộc) */
        private String phone;
    }

    /**
     * Response trả về cho POS sau khi tạo hóa đơn thành công.
     */
    @Data
    public static class EInvoiceResult {
        /** Số hóa đơn Viettel cấp, vd: "K24TXM5" — chỉ có sau khi ISSUED */
        private String invoiceNo;
        /** Mã nháp Viettel — chỉ có khi status=DRAFT (thay cho invoiceNo) */
        private String reservationCode;

        /** Transaction ID Viettel */
        private String transactionID;


        /** Timestamp ms hóa đơn được phát hành */
        private Long invoiceIssuedDate;

        /** templateCode */
        private String templateCode;

        /** invoiceSeries */
        private String invoiceSeries;

        /** File PDF hóa đơn (Base64) — null nếu chưa lấy */
        private String pdfBase64;

        /** Trạng thái: DRAFT | ISSUED | ERROR */
        private String status;

        /** Mô tả lỗi nếu status = ERROR */
        private String errorMessage;
    }
}