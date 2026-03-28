package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCategory;
import com.nhatnam.server.entity.pos.PosProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PosProductRepository extends JpaRepository<PosProduct, Long> {

    @Query("SELECT p FROM PosProduct p LEFT JOIN FETCH p.category " +
            "WHERE p.id = :productId AND p.storeId = :storeId")
    Optional<PosProduct> findByIdWithCategoryAndStoreId(
            @Param("productId") Long productId,
            @Param("storeId") Long storeId
    );

    /** Dùng trong PosService — lọc theo store */
    List<PosProduct> findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(Long storeId);

    /** Dùng trong PosService — lọc theo category (category đã verify thuộc store) */
    List<PosProduct> findByCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc(PosCategory category);

    /** Dùng trong PosExcelReportService — lấy TẤT CẢ product active (không lọc store)
     *  vì report cần hiển thị đầy đủ sản phẩm của ca */
    List<PosProduct> findByIsActiveTrueOrderByDisplayOrderAscNameAsc();

    /** Dùng trong PosExcelReportService — lấy product kèm category (tránh LazyInit) */
    @Query("SELECT p FROM PosProduct p JOIN FETCH p.category WHERE p.id = :id")
    Optional<PosProduct> findByIdWithCategory(@Param("id") Long id);

}