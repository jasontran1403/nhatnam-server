package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosIngredient;
import com.nhatnam.server.entity.pos.PosVariant;
import com.nhatnam.server.entity.pos.PosVariantIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;
public interface PosVariantIngredientRepository extends JpaRepository<PosVariantIngredient, Long> {
    List<PosVariantIngredient> findByVariant(PosVariant variant);

    void deleteByIngredientId(Long ingredientId);

    @Modifying
    void deleteByVariant(PosVariant variant);
}