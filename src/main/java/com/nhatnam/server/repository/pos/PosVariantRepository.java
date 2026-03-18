package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosProduct;
import com.nhatnam.server.entity.pos.PosVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosVariantRepository extends JpaRepository<PosVariant, Long> {
    List<PosVariant> findByProductAndIsActiveTrueOrderByDisplayOrderAsc(PosProduct product);
}