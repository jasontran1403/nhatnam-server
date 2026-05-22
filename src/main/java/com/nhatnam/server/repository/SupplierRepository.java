package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierRepository
        extends JpaRepository<Supplier, Long> {

    List<Supplier> findByIsActiveTrueOrderByNameAsc();
}