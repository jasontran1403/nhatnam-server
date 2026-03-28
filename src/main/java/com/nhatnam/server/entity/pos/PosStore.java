package com.nhatnam.server.entity.pos;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "pos_stores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PosStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String avatarUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "printer_ip", length = 50)
    private String printerIp;

    @Column(name = "shopee_rate", precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal shopeeRate = new BigDecimal("0.3305");

    @Column(name = "grab_rate", precision = 6, scale = 4)
    @Builder.Default
    private BigDecimal grabRate = new BigDecimal("0.2904");
}