package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PosIngredientRepository extends JpaRepository<PosIngredient, Long> {

    /** Dùng trong PosService — lọc theo store */
    List<PosIngredient> findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(Long storeId);

    /** Dùng trong PosExcelReportService — lấy TẤT CẢ ingredient active (không lọc store)
     *  vì report cần hiển thị đầy đủ nguyên liệu của ca */
    List<PosIngredient> findByIsActiveTrueOrderByDisplayOrderAscNameAsc();
}
