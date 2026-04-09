package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosDiscountProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PosDiscountProgramRepository extends JpaRepository<PosDiscountProgram, Long> {

    List<PosDiscountProgram> findAllByOrderByCreatedAtDesc();

    @Query("""
        SELECT p FROM PosDiscountProgram p
        WHERE p.status = 'ACTIVE'
          AND p.applyFrom <= :now
          AND p.applyTo   >= :now
    """)
    List<PosDiscountProgram> findActiveApplyingPrograms(@Param("now") long now);
}