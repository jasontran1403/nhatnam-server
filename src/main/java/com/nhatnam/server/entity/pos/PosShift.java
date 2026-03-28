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
    private String staffName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShiftStatus status;

    @Column(name = "open_time", nullable = false)
    private Long openTime;

    @Column(name = "close_time")
    private Long closeTime;

    @Column(name = "opening_cash", precision = 15, scale = 2)
    private BigDecimal openingCash;

    @Column(name = "closing_cash", precision = 15, scale = 2)
    private BigDecimal closingCash;

    @Column(name = "transfer_amount", precision = 15, scale = 2)
    private BigDecimal transferAmount;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "is_first_shift_of_day")
    private Boolean isFirstShiftOfDay = false;

    @Column(name = "shift_date", nullable = false)
    private String shiftDate;

    // ============ THÊM STORE_ID ============
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "store_name") // Lưu snapshot tên store
    private String storeName;
    // ========================================

    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftDenomination> openDenominations = new ArrayList<>();

    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftCloseDenomination> closeDenominations = new ArrayList<>();

    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftOpenInventory> openInventory = new ArrayList<>();

    @OneToMany(mappedBy = "shift", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private List<PosShiftCloseInventory> closeInventory = new ArrayList<>();
}