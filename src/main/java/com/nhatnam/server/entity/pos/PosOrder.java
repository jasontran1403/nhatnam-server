// entity/pos/PosOrder.java
package com.nhatnam.server.entity.pos;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderSource;
import com.nhatnam.server.enumtype.PosOrderStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_order")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosOrder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", unique = true, nullable = false)
    private String orderCode;

    @Column(name = "app_order_code", length = 50)
    private String appOrderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_source", nullable = false)
    private OrderSource orderSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PosOrderStatus status;

    @Column(name = "evoucher_id")
    private Long eVoucherId;

    @Column(name = "evoucher_code", length = 32)
    private String eVoucherCode;

    @Column(name = "evoucher_discount_amount", precision = 14, scale = 2)
    private BigDecimal eVoucherDiscountAmount;

    // ── Amounts ───────────────────────────────────────────────────
    // totalAmount  = tổng giá gốc RAW (trước discount, trước rate)
    // discountAmount = KM raw (trước rate)
    // finalAmount  = (totalAmount - discountAmount) × (1 - platformRate)  [net quán nhận]

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_vat_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalVatAmount;

    @Column(precision = 12, scale = 2) @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(length = 200)
    private String discountNote;

    @Column(name = "final_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalAmount;

    // ── Platform fee snapshot ────────────────────────────────────
    // Snapshot tại thời điểm tạo đơn để tránh thay đổi rate sau này
    @Column(name = "platform_rate", precision = 6, scale = 4) @Builder.Default
    private BigDecimal platformRate = BigDecimal.ZERO;        // vd: 0.3305

    @Column(name = "platform_fee_amount", precision = 12, scale = 2) @Builder.Default
    private BigDecimal platformFeeAmount = BigDecimal.ZERO;   // = (totalAmount - discountAmount) × platformRate

    // ── Customer snapshot ─────────────────────────────────────────
    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "payment_method", length = 30) @Builder.Default
    private String paymentMethod = "CASH";

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude @Builder.Default
    private List<PosOrderItem> items = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosStore store;

    @Column(name = "invoice_token", unique = true, length = 36)
    private String invoiceToken;

    // ── Thông tin xuất hóa đơn (do khách submit qua QR) ──────────
    @Column(name = "invoice_tax_code", length = 50)
    private String invoiceTaxCode;

    @Column(name = "invoice_company_name", length = 200)
    private String invoiceCompanyName;

    @Column(name = "invoice_email", length = 200)
    private String invoiceEmail;

    @Column(name = "invoice_submitted_at")
    private Long invoiceSubmittedAt;

    // ── Kết quả hóa đơn Viettel (sau khi tạo thành công) ────────
    @Column(name = "einvoice_no", length = 50)
    private String eInvoiceNo;

    @Column(name = "einvoice_issued_date")
    private Long eInvoiceIssuedDate;

    @Column(name = "einvoice_pdf_url", length = 500)
    private String eInvoicePdfUrl;

    @Column(name = "einvoice_status", length = 20)
    private String eInvoiceStatus;   // DRAFT | ISSUED | ERROR

    @Column(name = "einvoice_transaction_id", length = 100)
    private String eInvoiceTransactionId;  // Dùng để gửi CQT

    /**
     * Mẫu số + ký hiệu THỰC TẾ đã phát hành.
     * Cần lưu vì hệ thống dùng 2 dải khác nhau (POS máy tính tiền vs bán sỉ/lẻ);
     * lấy file PDF/XML từ Viettel phải truyền đúng mẫu số của chính hóa đơn đó.
     */
    @Column(name = "einvoice_template_code", length = 20)
    private String eInvoiceTemplateCode;

    @Column(name = "einvoice_series", length = 20)
    private String eInvoiceSeries;

    @Column(name = "invoice_address", length = 300)
    private String invoiceAddress;
}