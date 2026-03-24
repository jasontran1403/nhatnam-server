package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftCloseInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosShiftCloseInventoryRepository extends JpaRepository<PosShiftCloseInventory, Long> {
    List<PosShiftCloseInventory> findByShift(PosShift shift);

    @Query("SELECT i FROM PosShiftCloseInventory i " +
            "WHERE i.shift.id = :shiftId AND i.ingredient.id = :ingredientId")
    Optional<PosShiftCloseInventory> findByShiftAndIngredient_Id(
            @Param("shiftId") Long shiftId,
            @Param("ingredientId") Long ingredientId);
}
