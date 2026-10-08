package com.nhatnam.server.tools.vmb.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một chặng bay của booking. Bảng: vmb_segment.
 *
 * {@code departLocalMs} là thời điểm khởi hành TÍNH THEO MÚI GIỜ CỦA SÂN BAY
 * KHỞI HÀNH — lưu dạng epoch millis nhưng CỘT ĐÃ ĐƯỢC CHUYỂN SANG UTC dựa trên
 * IATA của {@code fromCode}. FE dựng lại theo múi giờ tương ứng để hiển thị và
 * so với giờ Việt Nam để tính đếm ngược.
 *
 * Ví dụ: chặng SGN-HND khởi hành "23Oct 23:15 giờ HCM". Giờ HCM = UTC+7 →
 * lưu departLocalMs = epoch của 23Oct 16:15 UTC. Sau này muốn hiển thị lại
 * "23Oct 23:15" thì format bằng ZoneId "Asia/Ho_Chi_Minh".
 *
 * segOrder giữ thứ tự khi list (JPA @OrderBy).
 */
@Entity
@Table(name = "vmb_segment", indexes = {
        @Index(name = "idx_vmb_segment_booking", columnList = "booking_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** IATA 3 ký tự, ví dụ "SGN", "HND" */
    @Column(name = "from_code", nullable = false, length = 3)
    private String fromCode;

    @Column(name = "to_code", nullable = false, length = 3)
    private String toCode;

    /** Epoch millis (UTC). FE chuyển sang giờ địa phương của fromCode để hiển thị. */
    @Column(name = "depart_local_ms")
    private Long departLocalMs;

    /** Vị trí trong hành trình, bắt đầu từ 0 */
    @Column(name = "seg_order", nullable = false)
    private Integer segOrder;
}
