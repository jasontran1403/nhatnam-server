package com.nhatnam.server.entity.pos;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.ShiftStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_shift")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShift {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opened_by", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private User openedBy;

    @Column(name = "staff_name", nullable = false)
    private String staffName; // Tên người đứng ca

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShiftStatus status; // OPEN | CLOSED

    // Thời gian mở/đóng ca (epoch millis)
    @Column(name = "open_time", nullable = false)
    private Long openTime;

    @Column(name = "close_time")
    private Long closeTime;

    // Tổng tiền mặt đầu ca (tính từ denominations)
    @Column(name = "opening_cash", precision = 15, scale = 2)
    private BigDecimal openingCash;

    // Tổng tiền mặt cuối ca
    @Column(name = "closing_cash", precision = 15, scale = 2)
    private BigDecimal closingCash;

    // Chuyển khoản cuối ca (nếu có)
    @Column(name = "transfer_amount", precision = 15, scale = 2)
    private BigDecimal transferAmount;

    @Column(columnDefinition = "TEXT")
    private String note; // Chi phí phát sinh

    // true nếu là ca đầu tiên trong ngày → bắt buộc nhập kho mở ca
    @Column(name = "is_first_shift_of_day")
    private Boolean isFirstShiftOfDay = false;

    // Ngày ca (yyyy-MM-dd dạng String để query theo ngày dễ)
    @Column(name = "shift_date", nullable = false)
    private String shiftDate; // "2026-02-23"

    // Mệnh giá tiền đầu ca
    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftDenomination> openDenominations = new ArrayList<>();

    // Mệnh giá tiền cuối ca
    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftCloseDenomination> closeDenominations = new ArrayList<>();

    // Kho nguyên liệu đầu ca
    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftOpenInventory> openInventory = new ArrayList<>();

    // Kho nguyên liệu cuối ca (user nhập khi đóng)
    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftCloseInventory> closeInventory = new ArrayList<>();
}
