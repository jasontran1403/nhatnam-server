package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pos_shift_stock_import")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PosShiftStockImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    private PosShift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private PosIngredient ingredient;

    @Column(name = "pack_qty", nullable = false)
    private Integer packQty;

    @Column(name = "imported_at", nullable = false)
    private Long importedAt;
}
