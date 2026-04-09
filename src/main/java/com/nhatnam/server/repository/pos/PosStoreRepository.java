package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosStoreRepository extends JpaRepository<PosStore, Long> {
    List<PosStore> findAllByActiveTrueOrderByNameAsc();
}

