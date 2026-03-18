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

    // Tìm 1 ingredient cụ thể trong variant (dùng để validate)
    Optional<PosVariantIngredient> findByVariantAndIngredient(PosVariant variant, PosIngredient ingredient);

    // Xóa toàn bộ ingredients của variant (dùng khi update)
    @Modifying
    void deleteByVariant(PosVariant variant);
}