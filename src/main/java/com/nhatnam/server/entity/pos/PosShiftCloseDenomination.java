package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pos_shift_close_denomination")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftCloseDenomination {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    @Column(nullable = false)
    private Integer denomination;

    @Column(nullable = false)
    private Integer quantity;
}
