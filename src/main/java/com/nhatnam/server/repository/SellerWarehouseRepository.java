package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SellerWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SellerWarehouseRepository extends JpaRepository<SellerWarehouse, Long> {
    Optional<SellerWarehouse> findBySellerId(Long sellerId);
    boolean existsBySellerId(Long sellerId);
}
