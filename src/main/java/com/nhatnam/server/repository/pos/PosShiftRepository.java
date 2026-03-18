package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosShift;
import com.nhatnam.server.enumtype.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
public interface PosShiftRepository extends JpaRepository<PosShift, Long> {
    Optional<PosShift> findByStatusAndOpenedById(ShiftStatus status, Long userId);
    List<PosShift> findTopByShiftDateOrderByOpenTimeDesc(String shiftDate);
    Optional<PosShift> findTopByShiftDateAndStatusOrderByCloseTimeDesc(String shiftDate, ShiftStatus status);
    List<PosShift> findByShiftDateOrderByOpenTimeAsc(String shiftDate);
    List<PosShift> findByShiftDateBetweenAndStatusOrderByShiftDateAscOpenTimeAsc(
            String fromDate, String toDate, ShiftStatus status);
    List<PosShift> findByShiftDateBetweenAndStatusOrderByShiftDateDescOpenTimeDesc(
            String fromDate, String toDate, ShiftStatus status);
    boolean existsByShiftDate(String shiftDate);

    //
    @Query("""
        SELECT s FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId AND s.status = :status
        ORDER BY s.openTime DESC
        LIMIT 1
    """)
    Optional<PosShift> findOpenShiftByStoreId(
            @Param("storeId") Long storeId,
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
     * Ca mới nhất trong ngày của store (bất kể status).
     */
    @Query("""
        SELECT s FROM PosShift s
        JOIN PosUserStore pus ON pus.user.id = s.openedBy.id
        WHERE pus.store.id = :storeId AND s.shiftDate = :shiftDate
        ORDER BY s.openTime DESC
        LIMIT 1
    """)
    Optional<PosShift> findLatestShiftByStoreAndDate(
            @Param("storeId")   Long storeId,
            @Param("shiftDate") String shiftDate);

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
}