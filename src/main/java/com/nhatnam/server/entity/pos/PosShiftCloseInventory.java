package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pos_shift_close_inventory")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftCloseInventory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    @ToString.Exclude @EqualsAndHashCode.Exclude
    private PosShift shift;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private PosIngredient ingredient;

    @Column(name = "pack_quantity", nullable = false)
    private Integer packQuantity = 0;

    @Column(name = "unit_quantity", nullable = false)
    private Integer unitQuantity = 0;
}
