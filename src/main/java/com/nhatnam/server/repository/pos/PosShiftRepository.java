package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.enumtype.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
public interface PosShiftRepository extends JpaRepository<PosShift, Long> {
    @Query("""
        SELECT s FROM PosShift s
        WHERE s.shiftDate BETWEEN :fromDate AND :toDate
          AND s.status = :status
          AND EXISTS (
              SELECT 1 FROM PosUserStore pus 
              WHERE pus.user.id = s.openedBy.id 
                AND pus.store.id = (
                    SELECT pus2.store.id FROM PosUserStore pus2 
                    WHERE pus2.user.id = :userId
                )
          )
        ORDER BY s.shiftDate DESC, s.openTime DESC
    """)
    List<PosShift> findShiftsByDateRangeAndUserStore(
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            @Param("status") ShiftStatus status,
            @Param("userId") Long userId);

    //
    @Query("""
        SELECT s FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId and s.openedBy.id = :userId AND s.status = :status
        ORDER BY s.openTime DESC
        LIMIT 1
    """)
    Optional<PosShift> findOpenShiftByStoreId(
            @Param("storeId") Long storeId,
            @Param("userId") Long userId,
            @Param("status")  ShiftStatus status);

    /**
     * Ca đang OPEN của store do user cụ thể mở.
     * Dùng khi đóng ca — chỉ user mở ca mới được đóng.
     */
    @Query("""
        SELECT s FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId
          AND s.openedBy.id = :userId
          AND s.status = :status
    """)
    Optional<PosShift> findOpenShiftByStoreIdAndUserId(
            @Param("storeId") Long storeId,
            @Param("userId")  Long userId,
            @Param("status")  ShiftStatus status);


    /**
     * Ca CLOSED mới nhất trong ngày của store.
     * Dùng để lấy kho cuối ca trước → kho đầu ca mới.
     */
    @Query("""
        SELECT s FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId
          AND s.shiftDate = :shiftDate
          AND s.status = 'CLOSED'
        ORDER BY s.closeTime DESC
        LIMIT 1
    """)
    Optional<PosShift> findLatestClosedShiftByStoreAndDate(
            @Param("storeId")   Long storeId,
            @Param("shiftDate") String shiftDate);

    /**
     * Tất cả ca trong ngày của store, sắp xếp mở ca ASC.
     */
    @Query("""
        SELECT s FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId AND s.shiftDate = :shiftDate
        ORDER BY s.openTime ASC
    """)
    List<PosShift> findShiftsByStoreAndDate(
            @Param("storeId")   Long storeId,
            @Param("shiftDate") String shiftDate);

    /**
     * Kiểm tra store đã có ca nào trong ngày chưa (để biết isFirstShift).
     */
    @Query("""
        SELECT COUNT(s) > 0 FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId AND s.shiftDate = :shiftDate
    """)
    boolean existsShiftByStoreAndDate(
            @Param("storeId")   Long storeId,
            @Param("shiftDate") String shiftDate);

    @Query("""
    SELECT COUNT(s) > 0 FROM PosShift s
    JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
    WHERE pus.store.id = :storeId AND s.shiftDate = :shiftDate
    AND s.status = :status
""")
    boolean existsShiftByStoreAndDateAndStatus(
            @Param("storeId")   Long storeId,
            @Param("shiftDate") String shiftDate,
            @Param("status")    ShiftStatus status);


}