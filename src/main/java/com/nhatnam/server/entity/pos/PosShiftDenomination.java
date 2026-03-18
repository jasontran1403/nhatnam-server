package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pos_shift_denomination")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftDenomination {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    // Mệnh giá: 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000
    @Column(nullable = false)
    private Integer denomination;

    @Column(nullable = false)
    private Integer quantity;
}
