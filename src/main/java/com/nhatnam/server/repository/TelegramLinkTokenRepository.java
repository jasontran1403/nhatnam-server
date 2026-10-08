package com.nhatnam.server.repository;

import com.nhatnam.server.entity.TelegramLinkToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TelegramLinkTokenRepository extends JpaRepository<TelegramLinkToken, Long> {

    Optional<TelegramLinkToken> findByToken(String token);

    /** Xóa token cũ của user để 1 user cùng lúc chỉ có 1 mã hoạt động */
    @Modifying
    @Query("DELETE FROM TelegramLinkToken t WHERE t.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** Dọn token hết hạn — chạy định kỳ */
    @Modifying
    @Query("DELETE FROM TelegramLinkToken t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") long now);
}
