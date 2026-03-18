package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PosShiftStockImportRepository extends JpaRepository<PosShiftStockImport, Long> {
    List<PosShiftStockImport> findByShift(PosShift shift);
    List<PosShiftStockImport> findByShiftAndIngredient(PosShift shift, PosIngredient ingredient);

    @Query("SELECT i FROM PosShiftStockImport i JOIN FETCH i.ingredient WHERE i.shift = :shift")
    List<PosShiftStockImport> findByShiftWithIngredient(@Param("shift") PosShift shift);

    List<PosShiftStockImport> findByShift_IdOrderByImportedAtDesc(Long shiftId);
}
