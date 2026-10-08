package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.Passenger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {

    /**
     * Search theo tên hành khách / tên công ty / số giấy tờ, filter theo companyId.
     *
     * ── 2026-09-15 ─────────────────────────────────────────
     * cccdNo và passportNo đã chuyển sang {@link com.nhatnam.server.tools.vmb.entity.PassengerDocument}.
     * Query LEFT JOIN sang documents để search theo docNumber vẫn hoạt động
     * (dù passenger chưa có giấy tờ nào vẫn hiển thị nếu match tên).
     *
     * ── Phân trang ─────────────────────────────────────────
     * KHÔNG fetch collection ở query phân trang này. Fetch collection + Pageable
     * sẽ khiến Hibernate load hết về memory (WARN HHH90003004). Service sẽ gọi
     * tiếp {@link #findAllFullByIds(List)} để lấy đầy đủ collection cho đúng
     * trang hiện tại.
     */
    @Query(value = """
        SELECT p FROM Passenger p
        LEFT JOIN p.documents d
        WHERE (:companyId IS NULL OR p.company.id = :companyId)
          AND (:q IS NULL OR :q = ''
              OR LOWER(p.fullName)     LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.company.name) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(d.docNumber)    LIKE LOWER(CONCAT('%', :q, '%')))
        GROUP BY p
        ORDER BY p.company.name ASC, p.fullName ASC
    """,
            countQuery = """
        SELECT COUNT(DISTINCT p) FROM Passenger p
        LEFT JOIN p.documents d
        WHERE (:companyId IS NULL OR p.company.id = :companyId)
          AND (:q IS NULL OR :q = ''
              OR LOWER(p.fullName)     LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.company.name) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(d.docNumber)    LIKE LOWER(CONCAT('%', :q, '%')))
    """)
    Page<Passenger> search(@Param("q") String q,
                           @Param("companyId") Long companyId,
                           Pageable pageable);

    /**
     * Fetch đầy đủ company + membershipCards + documents cho danh sách id.
     * Không phân trang → không còn WARN HHH90003004.
     */
    @EntityGraph(attributePaths = {"company", "membershipCards", "documents"})
    @Query("SELECT DISTINCT p FROM Passenger p WHERE p.id IN :ids")
    List<Passenger> findAllFullByIds(@Param("ids") List<Long> ids);

    @EntityGraph(attributePaths = {"company", "membershipCards", "documents"})
    @Query("SELECT p FROM Passenger p WHERE p.id = :id")
    Passenger findFullById(@Param("id") Long id);

    /** Đếm số passenger đang thuộc 1 công ty — dùng khi xóa công ty. */
    long countByCompanyId(Long companyId);
}