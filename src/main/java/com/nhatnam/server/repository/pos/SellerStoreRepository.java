package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.SellerStore;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SellerStoreRepository extends JpaRepository<SellerStore, Long> {
    Optional<SellerStore> findBySellerId(Long sellerId);
    boolean existsBySellerId(Long sellerId);
}
