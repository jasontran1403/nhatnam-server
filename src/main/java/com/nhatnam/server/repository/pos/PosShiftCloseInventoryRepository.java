package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.entity.pos.PosShiftCloseInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PosShiftCloseInventoryRepository extends JpaRepository<PosShiftCloseInventory, Long> {
    List<PosShiftCloseInventory> findByShift(PosShift shift);
}
