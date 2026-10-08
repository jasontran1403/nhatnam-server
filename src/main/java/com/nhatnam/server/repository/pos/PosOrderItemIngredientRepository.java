package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosOrderItem;
import com.nhatnam.server.entity.pos.PosOrderItemIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PosOrderItemIngredientRepository extends JpaRepository<PosOrderItemIngredient, Long> {
    List<PosOrderItemIngredient> findByOrderItem(PosOrderItem orderItem);

    @Query("SELECT oii FROM PosOrderItemIngredient oii " +
            "WHERE oii.orderItem.order.shift.id = :shiftId " +
            "AND oii.orderItem.order.status != com.nhatnam.server.enumtype.PosOrderStatus.CANCELLED")
    List<PosOrderItemIngredient> findByShiftId(@Param("shiftId") Long shiftId);

    @Query("SELECT oii FROM PosOrderItemIngredient oii " +
            "JOIN FETCH oii.orderItem oi " +
            "WHERE oi.order.shift.id = :shiftId " +
            "AND oi.order.status != com.nhatnam.server.enumtype.PosOrderStatus.CANCELLED")
    List<PosOrderItemIngredient> findByShiftIdFetchItem(@Param("shiftId") Long shiftId);
}