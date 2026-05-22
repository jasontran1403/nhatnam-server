package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OrderItemIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemIngredientRepository
        extends JpaRepository<OrderItemIngredient, Long> {

    @Query("""
        SELECT oii FROM OrderItemIngredient oii
        WHERE oii.orderItem.order.id = :orderId
    """)
    List<OrderItemIngredient> findByOrderId(@Param("orderId") Long orderId);
}
