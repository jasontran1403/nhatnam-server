package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * Admin list: chỉ task ADMIN type (do admin/superadmin tạo).
     */
    @Query("""
        SELECT t FROM Task t WHERE t.taskType = 'ADMIN' AND
          (:status IS NULL OR t.status = :status) AND
          (:priority IS NULL OR t.priority = :priority) AND
          (:category IS NULL OR t.category = :category) AND
          (:createdBy IS NULL OR t.createdBy = :createdBy) AND
          (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%',:q,'%'))) AND
          (:from IS NULL OR t.createdAt >= :from) AND
          (:to IS NULL OR t.createdAt <= :to)
        ORDER BY t.createdAt DESC
    """)
    Page<Task> findFilteredAdmin(
            @Param("status") String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("createdBy") Long createdBy,
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable
    );

    /**
     * Admin list by assignee: chỉ task ADMIN type.
     */
    @Query("""
        SELECT t FROM Task t WHERE t.taskType = 'ADMIN' AND t.id IN (
            SELECT ta.taskId FROM TaskAssignment ta WHERE ta.userId = :userId
        ) AND
          (:status IS NULL OR t.status = :status) AND
          (:priority IS NULL OR t.priority = :priority) AND
          (:category IS NULL OR t.category = :category) AND
          (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%',:q,'%'))) AND
          (:from IS NULL OR t.createdAt >= :from) AND
          (:to IS NULL OR t.createdAt <= :to)
        ORDER BY t.createdAt DESC
    """)
    Page<Task> findByAssigneeAdmin(
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable
    );

    /**
     * User list: task ADMIN được gán + task PERSONAL do user tạo.
     */
    @Query("""
        SELECT t FROM Task t WHERE (
            (t.taskType = 'ADMIN' AND t.id IN (SELECT ta.taskId FROM TaskAssignment ta WHERE ta.userId = :userId))
            OR
            (t.taskType = 'PERSONAL' AND t.createdBy = :userId)
        ) AND
          (:status IS NULL OR t.status = :status) AND
          (:priority IS NULL OR t.priority = :priority) AND
          (:category IS NULL OR t.category = :category) AND
          (:q IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%',:q,'%'))) AND
          (:from IS NULL OR t.createdAt >= :from) AND
          (:to IS NULL OR t.createdAt <= :to)
        ORDER BY t.createdAt DESC
    """)
    Page<Task> findUserTasks(
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("priority") String priority,
            @Param("category") String category,
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            Pageable pageable
    );

    @Query("SELECT DISTINCT t.category FROM Task t WHERE t.category IS NOT NULL ORDER BY t.category")
    List<String> findDistinctCategories();

    long countByStatus(String status);

    /** Chỉ đếm task ADMIN cho dashboard */
    @Query("SELECT COUNT(t) FROM Task t WHERE t.taskType = 'ADMIN' AND t.deadline IS NOT NULL AND t.deadline < :now AND t.status NOT IN ('COMPLETED','CANCELLED')")
    long countOverdueAdmin(@Param("now") long now);

    /** Lấy task ADMIN chưa hoàn thành có deadline — dùng để tính dueSoon 10% trong service */
    @Query("SELECT t FROM Task t WHERE t.taskType = 'ADMIN' AND t.deadline IS NOT NULL AND t.progress < 100 AND t.status NOT IN ('COMPLETED','CANCELLED')")
    List<Task> findActiveAdminWithDeadline();

    @Query("SELECT COUNT(t) FROM Task t WHERE t.deadline IS NOT NULL AND t.deadline < :now AND t.status NOT IN ('COMPLETED','CANCELLED')")
    long countOverdue(@Param("now") long now);

    @Query("SELECT COUNT(t) FROM Task t WHERE t.deadline IS NOT NULL AND t.deadline BETWEEN :now AND :soon AND t.status NOT IN ('COMPLETED','CANCELLED')")
    long countDueSoon(@Param("now") long now, @Param("soon") long soon);

    @Query("SELECT AVG(t.progress) FROM Task t WHERE t.status NOT IN ('CANCELLED')")
    Double avgProgress();

    /** Lấy tất cả task ADMIN cho dashboard */
    @Query("SELECT t FROM Task t WHERE t.taskType = 'ADMIN'")
    List<Task> findAllAdminTasks();
}