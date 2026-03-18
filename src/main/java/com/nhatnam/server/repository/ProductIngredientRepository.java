package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ProductIngredientRepository extends JpaRepository<ProductIngredient, Long> {
    // THÊM method này
    List<ProductIngredient> findByProductId(Long productId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ProductIngredient pi WHERE pi.product.id = :productId")
    void deleteByProductId(@Param("productId") Long productId);
}
