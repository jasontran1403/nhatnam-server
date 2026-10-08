package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.VatRate;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "`order`")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", unique = true, nullable = false)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer; // nullable - khách vãng lai

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "discount_rate", nullable = false)
    private Integer discountRate = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_rate", nullable = false)
    private VatRate vatRate = VatRate.ZERO;

    private String type;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "final_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal finalAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "customer_type", length = 20)
    private String customerType;        // COMPANY | RETAIL

    @Column(name = "company_name", length = 200)
    private String companyName;         // Tên công ty đầy đủ

    @Column(name = "short_name", length = 100)
    private String shortName;           // Tên rút gọn

    @Column(name = "tax_code", length = 20)
    private String taxCode;             // Mã số thuế

    @Column(name = "contact_name", length = 100)
    private String contactName;         // Người liên hệ

    @Column(name = "company_phone", length = 20)
    private String companyPhone;

    @Column(name = "company_address", length = 300)
    private String companyAddress;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;     // Địa chỉ giao hàng

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    // ── QR nhập thông tin xuất hóa đơn (khách tự nhập qua link public) ──
    @Column(name = "invoice_token", unique = true, length = 36)
    private String invoiceToken;

    /** Email nhận hóa đơn — có thể khác customerEmail */
    @Column(name = "invoice_email", length = 200)
    private String invoiceEmail;

    /** Lần cuối thông tin xuất hóa đơn được nhập/cập nhật */
    @Column(name = "invoice_submitted_at")
    private Long invoiceSubmittedAt;

    // ── Kết quả hóa đơn điện tử Viettel (đơn sỉ/lẻ) ──────────────────
    // ddl-auto=update sẽ tự tạo các cột này.
    @Column(name = "einvoice_no", length = 50)
    private String eInvoiceNo;

    @Column(name = "einvoice_issued_date")
    private Long eInvoiceIssuedDate;

    @Column(name = "einvoice_status", length = 20)
    private String eInvoiceStatus;          // ISSUED | ERROR

    @Column(name = "einvoice_transaction_id", length = 100)
    private String eInvoiceTransactionId;   // dùng khi gửi CQT

    @Column(name = "einvoice_pdf_url", length = 500)
    private String eInvoicePdfUrl;

    /** Mẫu số + ký hiệu thực tế đã phát hành — xem giải thích ở PosOrder */
    @Column(name = "einvoice_template_code", length = 20)
    private String eInvoiceTemplateCode;

    @Column(name = "einvoice_series", length = 20)
    private String eInvoiceSeries;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> orderItems;
}