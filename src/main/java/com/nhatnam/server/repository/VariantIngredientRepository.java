package com.nhatnam.server.repository;

import com.nhatnam.server.entity.VariantIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface VariantIngredientRepository extends JpaRepository<VariantIngredient, Long> {
    List<VariantIngredient> findByVariantId(Long variantId);

    @Modifying
    @Transactional
    @Query("DELETE FROM VariantIngredient vi WHERE vi.variant.id = :variantId")
    int deleteByVariantId(Long variantId);

}
