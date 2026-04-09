package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftOpenInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosShiftOpenInventoryRepository extends JpaRepository<PosShiftOpenInventory, Long> {
    List<PosShiftOpenInventory> findByShift(PosShift shift);

    @Query("SELECT i FROM PosShiftOpenInventory i " +
            "WHERE i.shift.id = :shiftId AND i.ingredientId = :ingredientId")
    Optional<PosShiftOpenInventory> findByShiftAndIngredient_Id(
            @Param("shiftId") Long shiftId,
            @Param("ingredientId") Long ingredientId);
}