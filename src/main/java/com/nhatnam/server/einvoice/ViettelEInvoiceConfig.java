package com.nhatnam.server.einvoice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "viettel.einvoice")
public class ViettelEInvoiceConfig {

    private String baseUrl;
    private String username;
    private String password;
    private String supplierTaxCode;

    /** Mẫu số + ký hiệu MẶC ĐỊNH — dùng cho hóa đơn bán sỉ/lẻ */
    private String templateCode;
    private String invoiceSeries;
    /** "1" = hóa đơn GTGT, "2" = hóa đơn bán hàng */
    private String invoiceType = "1";

    /**
     * Mẫu số + ký hiệu riêng cho hóa đơn khởi tạo từ MÁY TÍNH TIỀN (đơn POS).
     * Để trống → tự động dùng lại cấu hình mặc định ở trên.
     *
     * Theo TT78/NĐ123, ký hiệu hóa đơn máy tính tiền có ký tự "M" ở vị trí thứ 6,
     * VD: C26MOT. Mẫu số/ký hiệu này phải được đăng ký riêng với CQT và khai báo
     * trên hệ thống Viettel trước khi dùng.
     */
    private String posTemplateCode;
    private String posInvoiceSeries;
    private String posInvoiceType;

    private int    defaultTaxPercent = 8;
    private String authUrl = "https://api-vinvoice.viettel.vn/auth/login";

    /** HSM (test) hoặc USB_TOKEN (thật) */
    private String signMode = "HSM";

    private String pkcs11LibraryPath = "C:/Windows/System32/winca_csp11_v1.dll";
    private int    pkcs11SlotIndex   = 0;
    private String pkcs11Pin;
    private String certificateSerial;

    // ── Resolver theo kênh phát hành ────────────────────────────────
    // pos = true  → đơn POS (máy tính tiền)
    // pos = false → đơn bán sỉ/lẻ (dải hóa đơn hiện tại)

    public String templateCodeFor(boolean pos) {
        return pos && notBlank(posTemplateCode) ? posTemplateCode : templateCode;
    }

    public String invoiceSeriesFor(boolean pos) {
        return pos && notBlank(posInvoiceSeries) ? posInvoiceSeries : invoiceSeries;
    }

    public String invoiceTypeFor(boolean pos) {
        if (pos && notBlank(posInvoiceType)) return posInvoiceType;
        return notBlank(invoiceType) ? invoiceType : "1";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}