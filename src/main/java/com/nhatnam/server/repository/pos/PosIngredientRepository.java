package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PosIngredientRepository extends JpaRepository<PosIngredient, Long> {

    /** Dùng trong PosService — lọc theo store */
    List<PosIngredient> findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(Long storeId);

    List<PosIngredient> findByStoreIdAndIsActive(Long storeId, boolean isActive);

    List<PosIngredient> findByStoreId(Long storeId);
}
