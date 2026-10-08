package com.nhatnam.server.einvoice.service;

import com.nhatnam.server.entity.Order;
import com.nhatnam.server.repository.OrderRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi thông tin xuất hóa đơn của người mua lên đơn sỉ/lẻ.
 *
 * Dùng chung cho 2 đường vào:
 *   • Kế toán sửa trực tiếp  → PUT /api/pos/einvoice/sale/{orderCode}/invoice-info
 *   • Khách quét QR tự nhập  → POST /api/public/invoice/sale/{token}
 *
 * Quy tắc chặn: đã phát hành hóa đơn (eInvoiceStatus = ISSUED) thì không cho
 * sửa nữa — thông tin người mua đã nằm trên hóa đơn có giá trị pháp lý.
 * Khác với POS, đơn sỉ/lẻ KHÔNG có deadline 6 giờ.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class SaleInvoiceInfoService {

    private final OrderRepository orderRepo;

    /** Payload chung cho cả 2 đường vào. */
    @Data
    public static class InvoiceInfoRequest {
        private String taxCode;         // bắt buộc khi xuất HĐ công ty
        private String companyName;     // bắt buộc khi có MST
        private String companyAddress;
        private String invoiceEmail;    // bắt buộc
        private String contactName;
        private String companyPhone;
    }

    /** Ném IllegalArgumentException/IllegalStateException nếu dữ liệu không hợp lệ. */
    @Transactional
    public Order apply(Order order, InvoiceInfoRequest req) {
        if ("ISSUED".equals(order.getEInvoiceStatus())) {
            throw new IllegalStateException(
                    "Đơn đã phát hành hóa đơn " + order.getEInvoiceNo() + ", không thể sửa thông tin người mua");
        }

        String taxCode = trim(req.getTaxCode());
        String company = trim(req.getCompanyName());
        String email   = trim(req.getInvoiceEmail());

        if (isBlank(email))
            throw new IllegalArgumentException("Thiếu email nhận hóa đơn");
        if (!email.matches("^\\S+@\\S+\\.\\S+$"))
            throw new IllegalArgumentException("Email không hợp lệ");

        // Có MST thì bắt buộc có tên công ty, và ngược lại — tránh dữ liệu nửa vời
        if (!isBlank(taxCode) && isBlank(company))
            throw new IllegalArgumentException("Có mã số thuế thì phải nhập tên công ty");
        if (isBlank(taxCode) && !isBlank(company))
            throw new IllegalArgumentException("Có tên công ty thì phải nhập mã số thuế");

        order.setTaxCode(emptyToNull(taxCode));
        order.setCompanyName(emptyToNull(company));
        order.setCompanyAddress(emptyToNull(trim(req.getCompanyAddress())));
        order.setInvoiceEmail(email);
        if (!isBlank(req.getContactName()))  order.setContactName(trim(req.getContactName()));
        if (!isBlank(req.getCompanyPhone())) order.setCompanyPhone(trim(req.getCompanyPhone()));
        order.setCustomerType(isBlank(taxCode) ? "RETAIL" : "COMPANY");
        order.setInvoiceSubmittedAt(System.currentTimeMillis());
        order.setUpdatedAt(System.currentTimeMillis());

        Order saved = orderRepo.save(order);
        log.info("[SaleInvoice] cập nhật thông tin HĐ đơn {} — taxCode={}, email={}",
                order.getOrderCode(), taxCode, email);
        return saved;
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String emptyToNull(String s) { return isBlank(s) ? null : s; }
}
