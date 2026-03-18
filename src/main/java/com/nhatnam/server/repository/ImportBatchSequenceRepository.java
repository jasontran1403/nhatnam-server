package com.nhatnam.server.repository;

import com.nhatnam.server.entity.ImportBatchSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ImportBatchSequenceRepository extends JpaRepository<ImportBatchSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ImportBatchSequence s WHERE s.dateKey = :key")
    Optional<ImportBatchSequence> findByDateKeyForUpdate(@Param("key") String dateKey);
}