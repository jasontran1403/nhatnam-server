package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Thẻ thành viên (frequent-flyer) của một hành khách.
 * Một hành khách có thể có nhiều thẻ (mỗi hãng bay hay bay 1 thẻ).
 *
 * {@code passengerName} lưu riêng chứ không lấy từ Passenger.fullName vì tên
 * in trên thẻ FF có thể khác chút (ví dụ tên có/không có middle name, hoặc
 * gõ hoa/thường khác) — không được đổi sau khi phát hành.
 *
 * Bảng: vmb_membership_card
 */
@Entity
@Table(name = "vmb_membership_card", indexes = {
        @Index(name = "idx_vmb_mcard_pax", columnList = "passenger_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MembershipCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @Column(name = "passenger_name", nullable = false, length = 200)
    private String passengerName;

    @Column(name = "card_number", nullable = false, length = 40)
    private String cardNumber;

    /** Mã hãng bay IATA 2 ký tự (VN, VJ, JL, KE...) — có thể để tên đầy đủ nếu chưa biết mã */
    @Column(name = "airline_code", nullable = false, length = 30)
    private String airlineCode;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
}
