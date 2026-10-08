package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 1 dòng phí chi tiết của 1 vé. 1 Ticket có N TicketFee, và cùng 1
 * {@code feeType} có thể xuất hiện nhiều dòng (VD: 2 dòng "Phí hành lý"
 * cho 2 kiện, mỗi kiện 200k).
 *
 * ── Vì sao lưu String? ─────────────────────────────────────
 * Đồng bộ với {@code Ticket.basePrice/collectionFee/issuanceFee} — money.js
 * ở FE tính tổng. BE không cộng trừ, chỉ lưu chuỗi user gõ.
 *
 * ── orderIdx ────────────────────────────────────────────────
 * Giữ thứ tự user đã thêm trên UI (add sau xuống dưới). Không dùng id vì
 * id auto-increment không tương ứng "vị trí hiển thị".
 *
 * Bảng: vmb_ticket_fee
 */
@Entity
@Table(name = "vmb_ticket_fee", indexes = {
        @Index(name = "idx_vmb_ticket_fee_ticket", columnList = "ticket_id"),
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    /** Mã loại phí, xem {@link com.nhatnam.server.tools.vmb.enumtype.VmbFeeType}. */
    @Column(name = "fee_type", nullable = false, length = 40)
    private String feeType;

    @Column(name = "amount", nullable = false, length = 30)
    private String amount;

    /** Ghi chú riêng cho dòng phí (VD "kiện 20kg"). Có thể trống. */
    @Column(name = "note", length = 200)
    private String note;

    /** 0-based. Dùng cho @OrderBy khi load list. */
    @Column(name = "order_idx", nullable = false)
    @Builder.Default
    private Integer orderIdx = 0;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}