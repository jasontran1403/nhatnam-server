// entity/pos/PosCustomer.java — final version
package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pos_customer",
        uniqueConstraints = @UniqueConstraint(columnNames = "phone"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosCustomer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String name;

    // Tổng chi tiêu all-time (cộng dồn mỗi khi tạo order)
    @Column(nullable = false, precision = 16, scale = 2)
    @Builder.Default
    private BigDecimal totalSpend = BigDecimal.ZERO;

    @Column @Builder.Default
    private Long createdAt = System.currentTimeMillis();

    @Column @Builder.Default
    private Long updatedAt = System.currentTimeMillis();

    @PrePersist  void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate   void onUpdate() { updatedAt = System.currentTimeMillis(); }
}