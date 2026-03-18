package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pos_category",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_category_name_store_id",  // tên constraint rõ ràng hơn
                columnNames = {"name", "store_id"}   // unique theo store
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "single_price")
    private Boolean singlePrice = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /** Store mà category này thuộc về */
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<PosProduct> products = new ArrayList<>();
}