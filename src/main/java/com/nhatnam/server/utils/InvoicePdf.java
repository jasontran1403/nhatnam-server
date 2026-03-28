package com.nhatnam.server.utils;

import com.itextpdf.html2pdf.HtmlConverter;
import com.nhatnam.server.dto.InvoiceDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InvoicePdf {

    private static final String TEMPLATE_PATH = "pdf.html";
    private static final String VIETQR_URL = "https://img.vietqr.io/image/ACB-16198757-qr_only.jpg";

    public byte[] GenerateInvoicePdf(InvoiceDTO invoiceDTO) throws IOException {
        InputStream inputStream = InvoicePdf.class.getClassLoader().getResourceAsStream(TEMPLATE_PATH);
        if (inputStream == null) {
            throw new FileNotFoundException("Không tìm thấy template: " + TEMPLATE_PATH);
        }
        String htmlContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

        // ── Thông tin cơ bản ─────────────────────────────────────────────
        String orderCode       = invoiceDTO.getOrderCode()        != null ? invoiceDTO.getOrderCode()        : "N/A";
        String customerName    = invoiceDTO.getCustomerName()     != null ? invoiceDTO.getCustomerName()     : "Khách lẻ";
        String customerPhone   = invoiceDTO.getCustomerPhone()    != null ? invoiceDTO.getCustomerPhone()    : "N/A";
        String customerAddress = invoiceDTO.getShippingAddress()  != null ? invoiceDTO.getShippingAddress()  : "N/A";
        String orderNotes      = invoiceDTO.getNotes()            != null ? invoiceDTO.getNotes()            : "";
        String staffName       = "Nhân viên";

        BigDecimal totalBeforeDiscount = invoiceDTO.getTotalAmount()    != null ? invoiceDTO.getTotalAmount()    : BigDecimal.ZERO;
        BigDecimal discountAmount      = invoiceDTO.getDiscountAmount() != null ? invoiceDTO.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal vatAmount           = invoiceDTO.getVatAmount()      != null ? invoiceDTO.getVatAmount()      : BigDecimal.ZERO;
        BigDecimal finalAmount         = invoiceDTO.getFinalAmount()    != null ? invoiceDTO.getFinalAmount()    : BigDecimal.ZERO;

        String customerType    = invoiceDTO.getCustomerType();
        boolean isCompany      = "COMPANY".equalsIgnoreCase(customerType);
        String companyName     = invoiceDTO.getCompanyName()     != null ? invoiceDTO.getCompanyName()     : "";
        String shortName       = invoiceDTO.getShortName()       != null ? invoiceDTO.getShortName()       : "";
        String taxCode         = invoiceDTO.getTaxCode()         != null ? invoiceDTO.getTaxCode()         : "";
        String contactName     = invoiceDTO.getContactName()     != null ? invoiceDTO.getContactName()     : "";
        String deliveryAddress = invoiceDTO.getDeliveryAddress() != null ? invoiceDTO.getDeliveryAddress()
                : customerAddress;

        // Build customer info box HTML
        String customerInfoHtml;
        if (isCompany) {
            String companyPhone   = invoiceDTO.getCompanyPhone()   != null ? invoiceDTO.getCompanyPhone()   : customerPhone;
            String companyAddress = invoiceDTO.getCompanyAddress() != null ? invoiceDTO.getCompanyAddress() : "";

            customerInfoHtml =
                    "<div class=\"info-row\">" +
                            "<span class=\"label\">Tên công ty:</span>" +
                            "<span class=\"value\"><b>" + escapeHtml(companyName) + "</b></span>" +
                            "</div>" +
                            (!taxCode.isEmpty() ?
                                    "<div class=\"info-row\">" +
                                            "<span class=\"label\">MST:</span>" +
                                            "<span class=\"value\">" + escapeHtml(taxCode) + "</span>" +
                                            "</div>" : "") +
                            (!companyAddress.isEmpty() ?
                                    "<div class=\"info-row\">" +
                                            "<span class=\"label\">Địa chỉ:</span>" +
                                            "<span class=\"value\">" + escapeHtml(companyAddress) + "</span>" +
                                            "</div>" : "") +
                            "<div class=\"info-row\">" +
                            "<span class=\"label\">SĐT:</span>" +
                            "<span class=\"value\">" + escapeHtml(companyPhone) + "</span>" +
                            "</div>" +
                            // ── Người liên hệ ──
                            (!contactName.isEmpty() ?
                                    "<div class=\"info-row\">" +
                                            "<span class=\"label\">Người liên hệ/nhận hàng:</span>" +
                                            "<span class=\"value\">" + escapeHtml(contactName) + "</span>" +
                                            "</div>" : "") +
                            "<div class=\"info-row\">" +
                            "<span class=\"label\">SĐT người nhận:</span>" +
                            "<span class=\"value\">" + escapeHtml(customerPhone) + "</span>" +
                            "</div>" +
                            "<div class=\"info-row\">" +
                            "<span class=\"label\">Địa chỉ giao hàng:</span>" +
                            "<span class=\"value\">" + escapeHtml(deliveryAddress) + "</span>" +
                            "</div>";
        } else {
            // Khách lẻ
            customerInfoHtml =
                    "<div class=\"info-row\">" +
                            "<span class=\"label\">Khách hàng:</span>" +
                            "<span class=\"value\">Khách lẻ</span>" +
                            "</div>" +
                            // Tên người nhận (customerName từ order, thường là tên thật khi nhập)
                            (customerName != null && !customerName.equals("Khách lẻ") && !customerName.isEmpty() ?
                                    "<div class=\"info-row\">" +
                                            "<span class=\"label\">Người nhận:</span>" +
                                            "<span class=\"value\">" + escapeHtml(customerName) + "</span>" +
                                            "</div>" : "") +
                            "<div class=\"info-row\">" +
                            "<span class=\"label\">Địa chỉ nhận hàng:</span>" +
                            "<span class=\"value\">" + escapeHtml(customerAddress) + "</span>" +
                            "</div>" +
                            "<div class=\"info-row\">" +
                            "<span class=\"label\">Số điện thoại:</span>" +
                            "<span class=\"value\">" + escapeHtml(customerPhone) + "</span>" +
                            "</div>";
        }

        // VAT rate
        int vatRate = 0;
        if (totalBeforeDiscount.compareTo(BigDecimal.ZERO) > 0
                && vatAmount.compareTo(BigDecimal.ZERO) > 0) {
            vatRate = vatAmount.multiply(new BigDecimal("100"))
                    .divide(totalBeforeDiscount, 0, BigDecimal.ROUND_HALF_UP)
                    .intValue();
        }

        // ── Bảng sản phẩm ────────────────────────────────────────────────
        StringBuilder itemsHtml = new StringBuilder();
        int index = 1;
        if (invoiceDTO.getItems() != null) {
            for (InvoiceDTO.Item item : invoiceDTO.getItems()) {
                BigDecimal quantity     = item.getQuantity()     != null ? item.getQuantity()     : BigDecimal.ZERO;
                BigDecimal unitPrice    = item.getUnitPrice()    != null ? item.getUnitPrice()    : BigDecimal.ZERO;
                BigDecimal defaultPrice = item.getDefaultPrice() != null ? item.getDefaultPrice() : unitPrice;
                String unit             = item.getUnit()         != null ? item.getUnit()         : "Kg";
                BigDecimal subtotal     = item.getSubtotal()     != null ? item.getSubtotal()
                        : unitPrice.multiply(quantity);

                // Giá: gạch ngang nếu có giảm
                String priceDisplay = defaultPrice.compareTo(unitPrice) == 0
                        ? formatCurrency(unitPrice.doubleValue())
                        : formatCurrency(unitPrice.doubleValue())
                        + "<br><span style=\"text-decoration:line-through;color:#999;font-size:11px;\">"
                        + formatCurrency(defaultPrice.doubleValue()) + "</span>";

                itemsHtml.append("<tr>")
                        .append("<td class=\"center\">").append(index).append("</td>")
                        .append("<td>").append(escapeHtml(item.getProductName())).append("</td>")
                        .append("<td class=\"center\">").append(quantity.toPlainString()).append(" ").append(escapeHtml(unit)).append("</td>")
                        .append("<td class=\"center\">").append(priceDisplay).append("</td>")
                        .append("<td class=\"right\">").append(formatCurrency(subtotal.doubleValue())).append("</td>")
                        .append("</tr>");
                index++;
            }
        }

        // ── VAT breakdown ─────────────────────────────────────────────────
        StringBuilder vatBreakdownHtml = new StringBuilder();
        if (invoiceDTO.getVatBreakdown() != null && !invoiceDTO.getVatBreakdown().isEmpty()) {
            for (Map.Entry<Integer, BigDecimal> entry : invoiceDTO.getVatBreakdown().entrySet()) {
                vatBreakdownHtml.append("<tr>")
                        .append("<td class=\"lbl\" style=\"font-size:11px;\">VAT ").append(entry.getKey()).append("%:</td>")
                        .append("<td class=\"val\" style=\"font-size:11px;\">+")
                        .append(formatCurrency(entry.getValue().doubleValue())).append("</td>")
                        .append("</tr>");
            }
        }

        // ── QR Code ───────────────────────────────────────────────────────
        long   paymentAmount = finalAmount.setScale(0, BigDecimal.ROUND_HALF_UP).longValue();
        String addInfo       = URLEncoder.encode(orderCode, StandardCharsets.UTF_8);
        String accountName   = URLEncoder.encode("TA LE NAM DUC", StandardCharsets.UTF_8);
        String qrUrl         = VIETQR_URL + "?amount=" + paymentAmount
                + "&addInfo=" + addInfo + "&accountName=" + accountName;

        // ── Thời gian ─────────────────────────────────────────────────────
        String printTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"));

        // Dự kiến giao hàng
        Long   createdAt = invoiceDTO.getCreatedAt();
        String deliTime  = "N/A";
        if (createdAt != null) {
            LocalDateTime orderTime    = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(createdAt), ZoneId.of("Asia/Ho_Chi_Minh"));
            LocalDate     deliveryDate = orderTime.getHour() < 14
                    ? orderTime.toLocalDate().plusDays(3)
                    : orderTime.toLocalDate().plusDays(4);
            deliTime = deliveryDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }

        // ── Thay thế placeholder ──────────────────────────────────────────
        htmlContent = htmlContent.replace("{{customer_info}}", customerInfoHtml);
        htmlContent = htmlContent.replace("{{order_id}}",            escapeHtml(orderCode));
        htmlContent = htmlContent.replace("{{customer_name}}",       escapeHtml(customerName));
        htmlContent = htmlContent.replace("{{customer_address}}",    escapeHtml(customerAddress));
        htmlContent = htmlContent.replace("{{customer_phone}}",      escapeHtml(customerPhone));
        htmlContent = htmlContent.replace("{{order_note}}",          escapeHtml(orderNotes));
        htmlContent = htmlContent.replace("{{staffName}}",           escapeHtml(staffName));
        htmlContent = htmlContent.replace("{{print_time}}",          printTime);
        htmlContent = htmlContent.replace("{{deli_Time}}",           deliTime);
        htmlContent = htmlContent.replace("{{order_items}}",         itemsHtml.toString());
        htmlContent = htmlContent.replace("{{order_totalBeforeFee}}", formatCurrency(totalBeforeDiscount.doubleValue()));
        htmlContent = htmlContent.replace("{{order_salePrice}}",     formatCurrency(discountAmount.doubleValue()));
        htmlContent = htmlContent.replace("{{order_vat}}",           String.valueOf(vatRate));
        htmlContent = htmlContent.replace("{{vat_breakdown}}",       vatBreakdownHtml.toString());
        htmlContent = htmlContent.replace("{{order_vatPrice}}",      formatCurrency(vatAmount.doubleValue()));
        htmlContent = htmlContent.replace("{{order_totalAfterFee}}", formatCurrency(finalAmount.doubleValue()));
        htmlContent = htmlContent.replace("{{qr_url}}",              qrUrl);

        // Generate PDF
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            HtmlConverter.convertToPdf(
                    new ByteArrayInputStream(htmlContent.getBytes(StandardCharsets.UTF_8)),
                    outputStream);
            return outputStream.toByteArray();
        }
    }

    private static String formatCurrency(double amount) {
        return String.format("%,.0f đ", amount);
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}