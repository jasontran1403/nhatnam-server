package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftDenomination;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PosShiftDenominationRepository extends JpaRepository<PosShiftDenomination, Long> {
    List<PosShiftDenomination> findByShift(PosShift shift);
}