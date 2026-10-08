package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.TodoTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TodoTaskRepository extends JpaRepository<TodoTask, Long> {

    /** Deadline nằm trong khoảng [from, to). Sort theo deadline ASC. */
    @Query("""
        SELECT t FROM TodoTask t
        WHERE t.deadline >= :from AND t.deadline < :to
        ORDER BY t.deadline ASC, t.id ASC
    """)
    List<TodoTask> findByDeadlineRange(@Param("from") Long from, @Param("to") Long to);

    /** Task PENDING có deadline trong [from, to). */
    @Query("""
        SELECT t FROM TodoTask t
        WHERE t.status = 'PENDING'
          AND t.deadline >= :from AND t.deadline < :to
        ORDER BY t.deadline ASC
    """)
    List<TodoTask> findPendingInRange(@Param("from") Long from, @Param("to") Long to);
}