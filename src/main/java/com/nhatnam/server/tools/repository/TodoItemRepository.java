package com.nhatnam.server.tools.repository;

import com.nhatnam.server.tools.entity.TodoItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {

    /**
     * Tìm kiếm linh hoạt: lọc theo khoảng thời gian due, keyword, creator, status.
     * Tất cả đều tùy chọn (truyền null để bỏ qua).
     */
    @Query("SELECT t FROM TodoItem t WHERE " +
            "(:owner IS NULL OR t.owner = :owner) AND " +
            "(:status IS NULL OR t.status = :status) AND " +
            "(:creator IS NULL OR LOWER(t.creator) LIKE LOWER(CONCAT('%', :creator, '%'))) AND " +
            "(:q IS NULL OR LOWER(t.content) LIKE LOWER(CONCAT('%', :q, '%'))) AND " +
            "(:from IS NULL OR t.dueAt >= :from) AND " +
            "(:to IS NULL OR t.dueAt <= :to) " +
            "ORDER BY t.dueAt ASC")
    Page<TodoItem> search(
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("creator") String creator,
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable);
}