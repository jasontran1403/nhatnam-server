package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Order;
import com.nhatnam.server.enumtype.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderCode(String orderCode);

    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    @Query("SELECT o FROM Order o " +
            "LEFT JOIN FETCH o.orderItems oi " +
            "LEFT JOIN FETCH oi.orderItemIngredients " +
            "WHERE 1=1 " +
            "AND (:search IS NULL OR LOWER(o.customerName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "     OR LOWER(o.customerPhone) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "AND (:status IS NULL OR o.status = :status)")
    Page<Order> findAllWithItems(
            @Param("search") String search,
            @Param("status") OrderStatus status,
            Pageable pageable
    );

    // Nếu bạn muốn dùng @Query để tối ưu hoặc thêm join fetch
    @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt BETWEEN :start AND :end")
    long countByCreatedAtBetween(@Param("start") Long start, @Param("end") Long end);
}