package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.entity.pos.PosOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosOrderItemRepository extends JpaRepository<PosOrderItem, Long> {
    List<PosOrderItem> findByOrder(PosOrder order);
}
