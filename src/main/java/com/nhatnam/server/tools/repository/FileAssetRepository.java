package com.nhatnam.server.tools.repository;

import com.nhatnam.server.tools.entity.FileAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FileAssetRepository extends JpaRepository<FileAsset, Long> {

    /**
     * Danh sách có lọc. Mọi tham số cho phép null = bỏ qua điều kiện.
     * Thứ tự sắp xếp KHÔNG viết cứng ở đây mà truyền qua Pageable, vì màn hình
     * Tệp cho đổi giữa 6 kiểu sắp xếp (tên, thời gian, dung lượng × 2 chiều).
     *
     * exts truyền list rỗng sẽ làm câu IN không khớp gì cả, nên caller phải
     * truyền null khi không lọc — xem FileStorageService.search().
     */
    @Query("""
    SELECT f FROM FileAsset f
    WHERE (:owner IS NULL OR f.owner = :owner)
      AND (:q     IS NULL OR :q = '' OR LOWER(f.originalName) LIKE LOWER(CONCAT('%', :q, '%')))
      AND (:from  IS NULL OR f.createdAt >= :from)
      AND (:to    IS NULL OR f.createdAt <= :to)
      AND (:exts  IS NULL OR f.ext IN :exts)
""")
    Page<FileAsset> search(@Param("owner") String owner,
                           @Param("q") String q,
                           @Param("from") Long from,
                           @Param("to") Long to,
                           @Param("exts") Collection<String> exts,
                           Pageable pageable);

    /** Đổ dropdown lọc theo đuôi cho một user: [["xlsx", 12], ["pdf", 3], ...] */
    @Query("""
    SELECT f.ext, COUNT(f) FROM FileAsset f
    WHERE f.ext IS NOT NULL AND f.ext <> ''
      AND (:owner IS NULL OR f.owner = :owner)
    GROUP BY f.ext
    ORDER BY COUNT(f) DESC, f.ext ASC
""")
    List<Object[]> countByExt(@Param("owner") String owner);

    /**
     * Lấy các tên đang bị chiếm để tính hậu tố (n).
     * Lọc sẵn theo tiền tố ở tầng DB thay vì tải cả bảng về RAM — kho vài chục
     * nghìn file vẫn nhẹ.
     */
    @Query("SELECT f.originalName FROM FileAsset f WHERE LOWER(f.originalName) LIKE LOWER(CONCAT(:prefix, '%'))")
    List<String> findNamesStartingWith(@Param("prefix") String prefix);

    boolean existsByOriginalNameIgnoreCase(String originalName);
}
