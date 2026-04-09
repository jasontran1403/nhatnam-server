package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PosCategoryRepository extends JpaRepository<PosCategory, Long> {
    /** Lấy tất cả category active của store, sort theo displayOrder */
    List<PosCategory> findByStoreIdAndIsActiveTrueOrderByDisplayOrderAsc(Long storeId);
}