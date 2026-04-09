package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PosShiftStockImportRepository extends JpaRepository<PosShiftStockImport, Long> {
    List<PosShiftStockImport> findByShift(PosShift shift);

    List<PosShiftStockImport> findByShift_IdOrderByImportedAtDesc(Long shiftId);

     @Query("SELECT i.ingredientId, COALESCE(SUM(i.packQty), 0) " +
        "FROM PosShiftStockImport i " +
        "WHERE i.shift.id = :shiftId " +
        "GROUP BY i.ingredientId")
    List<Object[]> sumPackQtyByShiftId(@Param("shiftId") Long shiftId);
}
