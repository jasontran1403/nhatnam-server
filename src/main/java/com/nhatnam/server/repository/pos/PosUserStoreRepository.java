// src/main/java/com/nhatnam/server/repository/pos/PosUserStoreRepository.java

package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosUserStore;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PosUserStoreRepository extends JpaRepository<PosUserStore, Long> {

    /** Lấy store của 1 POS user */
    Optional<PosUserStore> findByUserId(Long userId);

    /** Kiểm tra user đã được gán store chưa */
    boolean existsByUserId(Long userId);
}