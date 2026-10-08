package com.nhatnam.server.tools.repository;

import com.nhatnam.server.tools.entity.MediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    /** Mới nhất lên trước — giống cách gallery điện thoại hiển thị */
    Page<MediaAsset> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /**
     * Danh sách có lọc. Mọi tham số đều cho phép null = bỏ qua điều kiện đó.
     *   favorite — chỉ lấy mục đã thả tim
     *   from/to  — khoảng thời gian tải lên (epoch millis)
     *   q        — tìm theo tên hiển thị
     */
    @Query("""
    SELECT m FROM MediaAsset m
    WHERE (:owner    IS NULL OR m.owner = :owner)
      AND (:favorite IS NULL OR m.favorite = :favorite)
      AND (:from     IS NULL OR m.createdAt >= :from)
      AND (:to       IS NULL OR m.createdAt <= :to)
      AND (:q        IS NULL OR :q = '' OR LOWER(m.originalName) LIKE LOWER(CONCAT('%', :q, '%')))
    ORDER BY m.createdAt DESC, m.id DESC
""")
    Page<MediaAsset> search(@Param("owner") String owner,
                            @Param("favorite") Boolean favorite,
                            @Param("from") Long from,
                            @Param("to") Long to,
                            @Param("q") String q,
                            Pageable pageable);

    /** Lọc theo danh sách ID (dùng cho album) + các bộ lọc khác */
    @Query("""
    SELECT m FROM MediaAsset m
    WHERE m.id IN :ids
      AND (:owner    IS NULL OR m.owner = :owner)
      AND (:favorite IS NULL OR m.favorite = :favorite)
      AND (:from     IS NULL OR m.createdAt >= :from)
      AND (:to       IS NULL OR m.createdAt <= :to)
      AND (:q        IS NULL OR :q = '' OR LOWER(m.originalName) LIKE LOWER(CONCAT('%', :q, '%')))
    ORDER BY m.createdAt DESC, m.id DESC
""")
    Page<MediaAsset> searchByIds(@Param("ids") List<Long> ids,
                                 @Param("owner") String owner,
                                 @Param("favorite") Boolean favorite,
                                 @Param("from") Long from,
                                 @Param("to") Long to,
                                 @Param("q") String q,
                                 Pageable pageable);
}
