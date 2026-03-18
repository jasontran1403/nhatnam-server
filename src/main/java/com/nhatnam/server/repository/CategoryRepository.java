package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Category;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByIsActiveTrueOrderByNameAsc();
    Optional<Category> findByIdAndIsActiveTrue(Long id);
    boolean existsByNameIgnoreCaseAndIsActiveTrue(String name);
    Optional<Category> findByName(String name);

    List<Category> findByIsActiveTrue(Pageable pageable);
}