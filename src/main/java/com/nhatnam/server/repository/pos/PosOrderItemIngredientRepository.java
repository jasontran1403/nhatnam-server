package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosOrderItem;
import com.nhatnam.server.entity.pos.PosOrderItemIngredient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosOrderItemIngredientRepository extends JpaRepository<PosOrderItemIngredient, Long> {
    List<PosOrderItemIngredient> findByOrderItem(PosOrderItem orderItem);

    // ← THÊM: lấy ingredients của nhiều order items (dùng trong buildSheet1)
    List<PosOrderItemIngredient> findByOrderItemIn(List<PosOrderItem> orderItems);
}