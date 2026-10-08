package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Mt5BotOpenPositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface Mt5BotOpenPositionRepository extends JpaRepository<Mt5BotOpenPositionEntity, Long> {
    List<Mt5BotOpenPositionEntity> findAllByAccountIdOrderByOpenTimeAsc(Long accountId);

    @Modifying
    @Query("delete from Mt5BotOpenPositionEntity p where p.accountId = :accountId")
    int deleteAllByAccountId(@Param("accountId") Long accountId);
}
