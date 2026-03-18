package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    List<ProductVariant> findByProductId(Long productId);

    List<ProductVariant> findByProductIdAndIsActiveTrue(Long productId);


    Optional<ProductVariant> findByIdAndIsActiveTrue(Long id);

    @Query("SELECT v FROM ProductVariant v " +
            "LEFT JOIN FETCH v.variantIngredients vi " +
            "LEFT JOIN FETCH vi.ingredient " +
            "WHERE v.id = :variantId AND v.isActive = true")
    Optional<ProductVariant> findByIdWithIngredients(@Param("variantId") Long variantId);
}