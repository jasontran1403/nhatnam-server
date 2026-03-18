package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftCloseDenomination;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PosShiftCloseDenominationRepository extends JpaRepository<PosShiftCloseDenomination, Long> {
    List<PosShiftCloseDenomination> findByShift(PosShift shift);
}