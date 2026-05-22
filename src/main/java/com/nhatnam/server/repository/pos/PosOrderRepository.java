package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.entity.pos.PosOrderItem;
import com.nhatnam.server.entity.pos.PosOrderItemIngredient;
import com.nhatnam.server.entity.pos.PosShift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosOrderRepository extends JpaRepository<PosOrder, Long> {
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
                WHEN HOUR(CONVERT_TZ(FROM_UNIXTIME(o.created_at/1000), '+00:00', '+07:00')) BETWEEN 5  AND 11 THEN 1
                WHEN HOUR(CONVERT_TZ(FROM_UNIXTIME(o.created_at/1000), '+00:00', '+07:00')) BETWEEN 12 AND 16 THEN 2
                WHEN HOUR(CONVERT_TZ(FROM_UNIXTIME(o.created_at/1000), '+00:00', '+07:00')) BETWEEN 17 AND 22 THEN 3
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
                WHEN HOUR(CONVERT_TZ(FROM_UNIXTIME(o.created_at/1000), '+00:00', '+07:00')) BETWEEN 5  AND 11 THEN '1 [5h-12h]'
                WHEN HOUR(CONVERT_TZ(FROM_UNIXTIME(o.created_at/1000), '+00:00', '+07:00')) BETWEEN 12 AND 16 THEN '2 [12h-17h]'
                WHEN HOUR(CONVERT_TZ(FROM_UNIXTIME(o.created_at/1000), '+00:00', '+07:00')) BETWEEN 17 AND 22 THEN '3 [17h-22h]'
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
