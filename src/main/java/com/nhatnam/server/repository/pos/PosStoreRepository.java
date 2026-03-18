package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosStoreRepository extends JpaRepository<PosStore, Long> {
    List<PosStore> findAllByActiveTrueOrderByNameAsc();

    // Tìm store theo tên (dùng cho export superadmin khi chỉ có storeName)
    List<PosStore> findByNameContainingIgnoreCase(String name);

    // Tìm chính xác theo tên (optional)
    Optional<PosStore> findByNameIgnoreCase(String name);
}

