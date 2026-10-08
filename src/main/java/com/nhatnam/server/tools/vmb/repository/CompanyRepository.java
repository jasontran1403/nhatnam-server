package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    List<Company> findAllByOrderByNameAsc();

    /** Chỉ công ty gốc (parent_id IS NULL), sort theo tên. */
    List<Company> findByParentIdIsNullOrderByNameAsc();

    /** Chi nhánh của 1 công ty mẹ. */
    List<Company> findByParentIdOrderByCreatedAtAsc(Long parentId);

    Optional<Company> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    Optional<Company> findByTaxId(String taxId);

    boolean existsByTaxId(String taxId);
}
