package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftOpenInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PosShiftOpenInventoryRepository extends JpaRepository<PosShiftOpenInventory, Long> {
    List<PosShiftOpenInventory> findByShift(PosShift shift);
}