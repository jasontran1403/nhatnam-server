package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosVoucherTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosVoucherTemplateRepository
        extends JpaRepository<PosVoucherTemplate, Long> {
    List<PosVoucherTemplate> findByStoreIdAndActiveTrue(Long storeId);
}
