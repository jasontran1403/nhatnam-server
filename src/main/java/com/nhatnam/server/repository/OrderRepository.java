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
    @Query("""
    SELECT o FROM Order o
    WHERE o.status NOT IN ('DELETED')
      AND o.createdAt BETWEEN :fromTs AND :toTs
      AND (:q IS NULL OR :q = '' OR
           o.orderCode    LIKE %:q% OR
           o.customerName LIKE %:q% OR
           o.customerPhone LIKE %:q%)
    ORDER BY o.createdAt DESC
""")
    Page<Order> searchAll(
            @Param("fromTs") Long fromTs,
            @Param("toTs")   Long toTs,
            @Param("q")      String q,
            Pageable pageable);

    @Query("""
    SELECT o FROM Order o
    WHERE o.user.id = :userId
      AND o.status NOT IN ('DELETED')
      AND o.createdAt BETWEEN :fromTs AND :toTs
      AND (:q IS NULL OR :q = '' OR
           o.orderCode    LIKE %:q% OR
           o.customerName LIKE %:q% OR
           o.customerPhone LIKE %:q%)
    ORDER BY o.createdAt DESC
""")
    Page<Order> searchByUser(
            @Param("userId") Long userId,
            @Param("fromTs")  Long fromTs,
            @Param("toTs")    Long toTs,
            @Param("q")       String q,
            Pageable pageable);

    Optional<Order> findByOrderCode(String orderCode);

    Optional<Order> findByInvoiceToken(String invoiceToken);

    /** Tra ngược từ số hóa đơn — dùng để biết hóa đơn thuộc dải nào khi lấy PDF/XML */
    @Query("SELECT o FROM Order o WHERE o.eInvoiceNo = :invoiceNo")
    Optional<Order> findByEInvoiceNo(@Param("invoiceNo") String invoiceNo);

    /**
     * Danh sách đơn sỉ/lẻ dùng cho màn hình xuất hóa đơn điện tử (ACCOUNTANT).
     * Bỏ qua đơn đã hủy / thất bại.
     * :type  — lọc theo loại đơn (SI / LE ...), null = tất cả.
     * :q     — tìm theo mã đơn / tên KH / SĐT / MST, null = tất cả.
     */
    @Query("""
    SELECT o FROM Order o
    WHERE o.createdAt BETWEEN :fromTs AND :toTs
      AND o.status NOT IN (com.nhatnam.server.enumtype.OrderStatus.CANCELLED,
                           com.nhatnam.server.enumtype.OrderStatus.FAILED)
      AND (:type IS NULL OR :type = '' OR o.type = :type)
      AND (:q IS NULL OR :q = '' OR
           o.orderCode     LIKE %:q% OR
           o.customerName  LIKE %:q% OR
           o.customerPhone LIKE %:q% OR
           o.companyName   LIKE %:q% OR
           o.taxCode       LIKE %:q%)
    ORDER BY o.createdAt DESC
""")
    Page<Order> findForEInvoice(
            @Param("fromTs") Long fromTs,
            @Param("toTs")   Long toTs,
            @Param("type")   String type,
            @Param("q")      String q,
            Pageable pageable);

    /** Các loại đơn (type) đang tồn tại — dùng đổ dropdown filter */
    @Query("SELECT DISTINCT o.type FROM Order o WHERE o.type IS NOT NULL AND o.type <> ''")
    List<String> findDistinctTypes();

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

    @Query("""
    SELECT DISTINCT o FROM Order o
    LEFT JOIN FETCH o.orderItems oi
    WHERE o.createdAt BETWEEN :fromTs AND :toTs
    AND o.status = 'COMPLETED'
    ORDER BY o.createdAt ASC
""")
    List<Order> findCompletedWithIngredientsBetween(
            @Param("fromTs") long fromTs,
            @Param("toTs")   long toTs);
}