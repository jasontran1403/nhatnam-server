package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.entity.pos.PosShift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosOrderRepository extends JpaRepository<PosOrder, Long> {
    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(created_at / 1000)) AS date,
           SUM(final_amount) AS daily_revenue
    FROM pos_order
    WHERE store_id = :storeId
      AND created_at BETWEEN :fromTs AND :toTs
      AND status = 'COMPLETED'
    GROUP BY date
    ORDER BY date
    """, nativeQuery = true)
    List<Object[]> findDailyRevenue(
            @Param("storeId") Long storeId,
            @Param("fromTs")  long fromTs,
            @Param("toTs")    long toTs
    );

    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(o.created_at / 1000)) AS date,
           FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(o.created_at / 1000))) / 60) AS minute_of_day,
           SUM(oi.quantity) AS product_count,
           SUM(oi.subtotal) AS total_revenue
    FROM pos_order o
    JOIN pos_order_item oi ON oi.order_id = o.id
    WHERE o.store_id = :storeId
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND o.status NOT IN ('CANCELLED', 'DELETED')
    GROUP BY date, minute_of_day
    ORDER BY date, minute_of_day
    """, nativeQuery = true)
    List<Object[]> findProductHeatmapByMinute(
            @Param("storeId") Long storeId,
            @Param("fromTs")  long fromTs,
            @Param("toTs")    long toTs
    );

    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(o.created_at / 1000)) AS date,
           FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(o.created_at / 1000))) / 60) AS minute_of_day,
           SUM(oi.quantity) AS product_count,
           SUM(oi.subtotal) AS total_revenue
    FROM pos_order o
    JOIN pos_order_item oi ON oi.order_id = o.id
    WHERE o.store_id = :storeId
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND o.status NOT IN ('CANCELLED', 'DELETED')
      AND oi.product_name IN (:productNames)
    GROUP BY date, minute_of_day
    ORDER BY date, minute_of_day
    """, nativeQuery = true)
    List<Object[]> findProductHeatmapByMinuteAndProductNames(
            @Param("storeId")      Long storeId,
            @Param("fromTs")       long fromTs,
            @Param("toTs")         long toTs,
            @Param("productNames") List<String> productNames
    );

    // ═══════════ INGREDIENT HEATMAP ═══════════

    @Query(value = """
    SELECT sub.date, sub.minute_of_day,
           SUM(sub.ing_count)  AS ingredient_count,
           SUM(sub.order_rev)  AS total_revenue
    FROM (
        SELECT DATE(FROM_UNIXTIME(o.created_at / 1000)) AS date,
               FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(o.created_at / 1000))) / 60) AS minute_of_day,
               SUM(oii.selected_count * oi.quantity) AS ing_count,
               MAX(o.total_amount) AS order_rev
        FROM pos_order o
        JOIN pos_order_item oi ON oi.order_id = o.id
        JOIN pos_order_item_ingredient oii ON oii.order_item_id = oi.id
        JOIN pos_ingredient ing ON ing.id = oii.ingredient_id
        WHERE o.store_id = :storeId
          AND o.created_at BETWEEN :fromTs AND :toTs
          AND o.status NOT IN ('CANCELLED', 'DELETED')
          AND ing.ingredient_type = 'MAIN'
          AND ing.store_id = :storeId
        GROUP BY date, minute_of_day, o.id
    ) sub
    GROUP BY sub.date, sub.minute_of_day
    ORDER BY sub.date, sub.minute_of_day
    """, nativeQuery = true)
    List<Object[]> findIngredientHeatmapByMinute(
            @Param("storeId") Long storeId,
            @Param("fromTs")  long fromTs,
            @Param("toTs")    long toTs
    );

    @Query(value = """
    SELECT sub.date, sub.minute_of_day,
           SUM(sub.ing_count)  AS ingredient_count,
           SUM(sub.order_rev)  AS total_revenue
    FROM (
        SELECT DATE(FROM_UNIXTIME(o.created_at / 1000)) AS date,
               FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(o.created_at / 1000))) / 60) AS minute_of_day,
               SUM(oii.selected_count * oi.quantity) AS ing_count,
               MAX(o.total_amount) AS order_rev
        FROM pos_order o
        JOIN pos_order_item oi ON oi.order_id = o.id
        JOIN pos_order_item_ingredient oii ON oii.order_item_id = oi.id
        WHERE o.store_id = :storeId
          AND o.created_at BETWEEN :fromTs AND :toTs
          AND o.status NOT IN ('CANCELLED', 'DELETED')
          AND oii.ingredient_id IN (:ingredientIds)
        GROUP BY date, minute_of_day, o.id
    ) sub
    GROUP BY sub.date, sub.minute_of_day
    ORDER BY sub.date, sub.minute_of_day
    """, nativeQuery = true)
    List<Object[]> findIngredientHeatmapByMinuteAndIds(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        long fromTs,
            @Param("toTs")          long toTs,
            @Param("ingredientIds") List<Long> ingredientIds
    );

    // Tìm kiếm POS orders với pagination + search + time filter
    @Query("""
    SELECT o FROM PosOrder o
    WHERE o.store.id = :storeId
      AND o.status NOT IN ('DELETED')
      AND o.createdAt BETWEEN :fromTs AND :toTs
      AND (:q IS NULL OR :q = '' OR
           o.orderCode LIKE %:q% OR
           o.customerName LIKE %:q% OR
           o.customerPhone LIKE %:q%)
    ORDER BY o.createdAt DESC
""")
    Page<PosOrder> searchByStore(
            @Param("storeId") Long storeId,
            @Param("fromTs")  Long fromTs,
            @Param("toTs")    Long toTs,
            @Param("q")       String q,
            Pageable pageable);

    // SuperAdmin — tất cả stores
    @Query("""
    SELECT o FROM PosOrder o
    WHERE o.status NOT IN ('DELETED')
      AND o.createdAt BETWEEN :fromTs AND :toTs
      AND (:q IS NULL OR :q = '' OR
           o.orderCode LIKE %:q% OR
           o.customerName LIKE %:q% OR
           o.customerPhone LIKE %:q%)
    ORDER BY o.createdAt DESC
""")
    Page<PosOrder> searchAll(
            @Param("fromTs") Long fromTs,
            @Param("toTs")   Long toTs,
            @Param("q")      String q,
            Pageable pageable);

    Optional<PosOrder> findByInvoiceToken(String invoiceToken);

    Optional<PosOrder> findByOrderCode(String orderCode);

    /** Tra ngược từ số hóa đơn — dùng để biết hóa đơn thuộc dải nào khi lấy PDF/XML */
    @Query("SELECT o FROM PosOrder o WHERE o.eInvoiceNo = :invoiceNo")
    Optional<PosOrder> findByEInvoiceNo(@Param("invoiceNo") String invoiceNo);

    Optional<PosOrder> findByAppOrderCode(String appOrderCode);

    @Query("""
        SELECT o FROM PosOrder o
        WHERE o.store.id = :storeId
          AND o.createdAt BETWEEN :fromTs AND :toTs
          AND o.status NOT IN ('DELETED')
        ORDER BY o.createdAt DESC
    """)
    org.springframework.data.domain.Page<PosOrder> findByStoreIdAndTimeRange(
            @Param("storeId") Long storeId,
            @Param("fromTs")  Long fromTs,
            @Param("toTs")    Long toTs,
            org.springframework.data.domain.Pageable pageable);

    /** Dùng cho ACCOUNTANT / SUPERADMIN — không filter theo store */
    @Query("""
        SELECT o FROM PosOrder o
        WHERE o.createdAt BETWEEN :fromTs AND :toTs
          AND o.status NOT IN ('DELETED')
        ORDER BY o.createdAt DESC
    """)
    org.springframework.data.domain.Page<PosOrder> findAllByTimeRange(
            @Param("fromTs") Long fromTs,
            @Param("toTs")   Long toTs,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
    SELECT o FROM PosOrder o
    WHERE o.store.id = :storeId
      AND o.customerPhone = :phone
      AND o.status NOT IN ('DELETED')
    ORDER BY o.createdAt DESC
    """)
    Page<PosOrder> findByStoreIdAndCustomerPhone(
            @Param("storeId") Long storeId,
            @Param("phone")   String phone,
            Pageable pageable);

    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(created_at / 1000)) AS date,
           FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(created_at / 1000))) / 60) AS minute_of_day,
           COUNT(*) AS order_count,
           SUM(total_amount) AS total_revenue
    FROM pos_order
    WHERE store_id = :storeId
      AND created_at BETWEEN :fromTs AND :toTs
      AND status NOT IN ('CANCELLED', 'DELETED')
    GROUP BY date, minute_of_day
    ORDER BY date, minute_of_day
    """, nativeQuery = true)
    List<Object[]> findHeatmapDataByMinute(
            @Param("storeId") Long storeId,
            @Param("fromTs")  long fromTs,
            @Param("toTs")    long toTs
    );

    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(o.created_at / 1000)) AS date,
           FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(o.created_at / 1000))) / 60) AS minute_of_day,
           COUNT(DISTINCT o.id) AS order_count,
           SUM(o.total_amount) AS total_revenue
    FROM pos_order o
    JOIN pos_order_item oi ON oi.order_id = o.id
    WHERE o.store_id = :storeId
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND o.status NOT IN ('CANCELLED', 'DELETED')
      AND oi.product_name IN (:productNames)
    GROUP BY date, minute_of_day
    ORDER BY date, minute_of_day
    """, nativeQuery = true)
    List<Object[]> findHeatmapDataByMinuteAndProductNames(
            @Param("storeId")      Long storeId,
            @Param("fromTs")       long fromTs,
            @Param("toTs")         long toTs,
            @Param("productNames") List<String> productNames
    );

    @Query(value = """
    SELECT DISTINCT oi.product_name
    FROM pos_order_item oi
    JOIN pos_order o ON oi.order_id = o.id
    WHERE o.store_id = :storeId
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND o.status NOT IN ('CANCELLED', 'DELETED')
    ORDER BY oi.product_name ASC
    """, nativeQuery = true)
    List<String> findDistinctProductNamesByStoreAndTimeRange(
            @Param("storeId") Long storeId,
            @Param("fromTs")  long fromTs,
            @Param("toTs")    long toTs
    );

    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(o.created_at / 1000)) AS date,
           FLOOR(TIME_TO_SEC(TIME(FROM_UNIXTIME(o.created_at / 1000))) / 60) AS minute_of_day,
           COUNT(*) AS order_count,
           SUM(o.total_amount) AS total_revenue
    FROM pos_order o
    JOIN pos_order_item oi ON oi.order_id = o.id
    WHERE o.store_id = :storeId
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND o.status NOT IN ('CANCELLED', 'DELETED')
      AND oi.product_id IN (:productIds)
    GROUP BY date, minute_of_day
    ORDER BY date, minute_of_day
    """, nativeQuery = true)
    List<Object[]> findHeatmapDataByMinuteAndProducts(
            @Param("storeId")     Long storeId,
            @Param("fromTs")      long fromTs,
            @Param("toTs")        long toTs,
            @Param("productIds")  List<Long> productIds
    );

    @Query("""
        SELECT o.customerPhone, SUM(o.finalAmount)
        FROM PosOrder o
        WHERE o.customerPhone IS NOT NULL
          AND o.status = 'COMPLETED'
          AND o.createdAt BETWEEN :from AND :to
        GROUP BY o.customerPhone
    """)
    List<Object[]> sumSpendByCustomerInRange(
            @Param("from") Long from, @Param("to") Long to);

    // ── Tổng chi tiêu tích lũy (mọi thời điểm) theo SĐT ─────────────
    // Dùng cho màn Khách hàng: "đ chi tiêu" = tổng tất cả đơn của khách,
    // không lọc thời gian. Loại đơn đã xoá/huỷ.
    // Cộng theo finalAmount (= tổng subtotal từng dòng, đã tính đúng giá
    // định lượng cho hàng bán theo kg/xé lẻ). KHÔNG dùng totalAmount vì
    // header totalAmount của đơn cũ tính theo giá gốc → sai với hàng định lượng.

    /** Tổng chi tiêu của toàn bộ khách trong 1 store — trả [phone, sum]. */
    @Query("""
        SELECT o.customerPhone, SUM(o.finalAmount)
        FROM PosOrder o
        WHERE o.store.id = :storeId
          AND o.customerPhone IS NOT NULL
          AND o.status NOT IN ('DELETED', 'CANCELLED')
        GROUP BY o.customerPhone
    """)
    List<Object[]> sumLifetimeSpendByStore(@Param("storeId") Long storeId);

    /** Tổng chi tiêu của 1 khách theo SĐT. */
    @Query("""
        SELECT COALESCE(SUM(o.finalAmount), 0)
        FROM PosOrder o
        WHERE o.store.id = :storeId
          AND o.customerPhone = :phone
          AND o.status NOT IN ('DELETED', 'CANCELLED')
    """)
    java.math.BigDecimal sumLifetimeSpendByPhone(
            @Param("storeId") Long storeId, @Param("phone") String phone);

    List<PosOrder> findByShiftOrderByCreatedAtDesc(PosShift shift);

    @Query("SELECT MAX(o.orderCode) FROM PosOrder o WHERE o.orderCode LIKE :prefix%")
    Optional<String> findMaxOrderCodeByPrefix(@Param("prefix") String prefix);

    @Query(value = """
    SELECT
        DATE_FORMAT(FROM_UNIXTIME(o.created_at/1000), '%b %Y') AS month,
        YEAR(FROM_UNIXTIME(o.created_at/1000))  AS yr,
        MONTH(FROM_UNIXTIME(o.created_at/1000)) AS mo,
        CASE
            WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5  AND 11 THEN 1
            WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN 2
            WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN 3
            ELSE 0
        END AS shift,
        SUM(o.final_amount) AS revenue,
        COUNT(o.id)         AS orderCount
    FROM pos_order o
    WHERE o.store_id = :storeId
      AND o.status   = 'COMPLETED'
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND (:categoryNames IS NULL OR EXISTS (
            SELECT 1 FROM pos_order_item i
            WHERE i.order_id = o.id
              AND i.category_name IN (:categoryNames)
      ))
            GROUP BY DATE_FORMAT(FROM_UNIXTIME(o.created_at/1000), '%b %Y'),
                                   YEAR(FROM_UNIXTIME(o.created_at/1000)),
                                   MONTH(FROM_UNIXTIME(o.created_at/1000)),
                                   CASE WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5 AND 11 THEN 1
                                        WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN 2
                                        WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN 3
                                        ELSE 0 END
                          ORDER BY YEAR(FROM_UNIXTIME(o.created_at/1000)),
                                   MONTH(FROM_UNIXTIME(o.created_at/1000)),
                                   CASE WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5 AND 11 THEN 1
                                        WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN 2
                                        WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN 3
                                        ELSE 0 END
    """, nativeQuery = true)
    List<Object[]> findMonthlyByShift(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        Long fromTs,
            @Param("toTs")          Long toTs,
            @Param("categoryNames") List<String> categoryNames
    );

    // Chart 2: monthly stacked by shift (fixed, không filter shift)
    @Query(value = """
    SELECT
        DATE_FORMAT(FROM_UNIXTIME(o.created_at/1000), '%b %Y') AS month,
        YEAR(FROM_UNIXTIME(o.created_at/1000))  AS yr,
        MONTH(FROM_UNIXTIME(o.created_at/1000)) AS mo,
        CASE
            WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5  AND 11 THEN '1 [5h-12h]'
            WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN '2 [12h-17h]'
            WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN '3 [17h-22h]'
            ELSE 'Khác'
        END AS shiftLabel,
        SUM(o.final_amount) AS revenue,
        COUNT(o.id)         AS orderCount
    FROM pos_order o
    WHERE o.store_id = :storeId
      AND o.status   = 'COMPLETED'
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND (:categoryNames IS NULL OR EXISTS (
            SELECT 1 FROM pos_order_item i
            WHERE i.order_id = o.id
              AND i.category_name IN (:categoryNames)
      ))
            GROUP BY DATE_FORMAT(FROM_UNIXTIME(o.created_at/1000), '%b %Y'),
                                                    YEAR(FROM_UNIXTIME(o.created_at/1000)),
                                                    MONTH(FROM_UNIXTIME(o.created_at/1000)),
                                                    CASE WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5 AND 11 THEN '1 [5h-12h]'
                                                         WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN '2 [12h-17h]'
                                                         WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN '3 [17h-22h]'
                                                         ELSE 'Khác' END
                                           ORDER BY YEAR(FROM_UNIXTIME(o.created_at/1000)),
                                                    MONTH(FROM_UNIXTIME(o.created_at/1000)),
                                                    CASE WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5 AND 11 THEN '1 [5h-12h]'
                                                         WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN '2 [12h-17h]'
                                                         WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN '3 [17h-22h]'
                                                         ELSE 'Khác' END
                               
    """, nativeQuery = true)
    List<Object[]> findMonthlyByShiftStacked(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        Long fromTs,
            @Param("toTs")          Long toTs,
            @Param("categoryNames") List<String> categoryNames
    );

    // Chart 2: monthly stacked by category
    @Query(value = """
    SELECT
        DATE_FORMAT(FROM_UNIXTIME(o.created_at/1000), '%b %Y') AS month,
        YEAR(FROM_UNIXTIME(o.created_at/1000))  AS yr,
        MONTH(FROM_UNIXTIME(o.created_at/1000)) AS mo,
        i.category_name AS categoryName,
        SUM(i.subtotal)  AS revenue,
        SUM(i.quantity)  AS orderCount
    FROM pos_order o
    JOIN pos_order_item i ON i.order_id = o.id
    WHERE o.store_id = :storeId
      AND o.status   = 'COMPLETED'
      AND o.created_at BETWEEN :fromTs AND :toTs
      AND i.category_name IN (:categoryNames)
            GROUP BY DATE_FORMAT(FROM_UNIXTIME(o.created_at/1000), '%b %Y'),
             YEAR(FROM_UNIXTIME(o.created_at/1000)),
             MONTH(FROM_UNIXTIME(o.created_at/1000)),
             i.category_name
            ORDER BY YEAR(FROM_UNIXTIME(o.created_at/1000)),
             MONTH(FROM_UNIXTIME(o.created_at/1000)),
             i.category_name
    """, nativeQuery = true)
    List<Object[]> findMonthlyByCategory(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        Long fromTs,
            @Param("toTs")          Long toTs,
            @Param("categoryNames") List<String> categoryNames
    );

    // Heatmap: 7 ngày × khung giờ
    @Query(value = """
    SELECT DATE(FROM_UNIXTIME(po.created_at / 1000)) as date,
           FLOOR(HOUR(FROM_UNIXTIME(po.created_at / 1000)) / 2) * 2 as hour_bucket,
           COUNT(*) as order_count,
           SUM(po.final_amount) as total_revenue
    FROM pos_order po
    WHERE po.store_id = :storeId
      AND po.created_at >= :fromTs
      AND po.created_at <= :toTs
      AND po.status != 'CANCELLED'
    GROUP BY date, hour_bucket
    ORDER BY date, hour_bucket
    """, nativeQuery = true)
    List<Object[]> findHeatmapData(
            @Param("storeId") Long storeId,
            @Param("fromTs")  long fromTs,
            @Param("toTs")    long toTs      // ← THÊM
    );

    @Query(value = """
        SELECT
            CASE
                WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5  AND 11 THEN 1
                WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN 2
                WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN 3
                ELSE 0
            END AS shift,
            SUM(o.final_amount) AS revenue,
            COUNT(o.id)         AS orderCount
        FROM pos_order o
        WHERE o.store_id = :storeId
          AND o.status   = 'COMPLETED'
          AND o.created_at BETWEEN :fromTs AND :toTs
          AND (:categoryNames IS NULL OR EXISTS (
                SELECT 1 FROM pos_order_item i
                WHERE i.order_id = o.id
                  AND i.category_name IN (:categoryNames)
          ))
        GROUP BY shift
        ORDER BY shift
        """, nativeQuery = true)
    List<Object[]> findByShiftInRange(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        Long fromTs,
            @Param("toTs")          Long toTs,
            @Param("categoryNames") List<String> categoryNames
    );

    // ── Chart 2: stacked by shift label trong 1 khoảng ─────────────
    @Query(value = """
        SELECT
            CASE
                WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 5  AND 11 THEN '1 [5h-12h]'
                WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 12 AND 16 THEN '2 [12h-17h]'
                WHEN HOUR(FROM_UNIXTIME(o.created_at/1000)) BETWEEN 17 AND 22 THEN '3 [17h-22h]'
                ELSE 'Khác'
            END AS shiftLabel,
            SUM(o.final_amount) AS revenue,
            COUNT(o.id)         AS orderCount
        FROM pos_order o
        WHERE o.store_id = :storeId
          AND o.status   = 'COMPLETED'
          AND o.created_at BETWEEN :fromTs AND :toTs
          AND (:categoryNames IS NULL OR EXISTS (
                SELECT 1 FROM pos_order_item i
                WHERE i.order_id = o.id
                  AND i.category_name IN (:categoryNames)
          ))
        GROUP BY shiftLabel
        ORDER BY shiftLabel
        """, nativeQuery = true)
    List<Object[]> findByShiftStackedInRange(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        Long fromTs,
            @Param("toTs")          Long toTs,
            @Param("categoryNames") List<String> categoryNames
    );

    // ── Chart 2: stacked by category trong 1 khoảng ────────────────
    @Query(value = """
        SELECT
            i.category_name AS categoryName,
            SUM(i.subtotal) AS revenue,
            SUM(i.quantity) AS orderCount
        FROM pos_order o
        JOIN pos_order_item i ON i.order_id = o.id
        WHERE o.store_id = :storeId
          AND o.status   = 'COMPLETED'
          AND o.created_at BETWEEN :fromTs AND :toTs
          AND i.category_name IN (:categoryNames)
        GROUP BY i.category_name
        ORDER BY i.category_name
        """, nativeQuery = true)
    List<Object[]> findByCategoryInRange(
            @Param("storeId")       Long storeId,
            @Param("fromTs")        Long fromTs,
            @Param("toTs")          Long toTs,
            @Param("categoryNames") List<String> categoryNames
    );

}