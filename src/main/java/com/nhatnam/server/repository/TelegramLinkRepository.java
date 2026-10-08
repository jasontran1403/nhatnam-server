package com.nhatnam.server.repository;

import com.nhatnam.server.entity.TelegramLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TelegramLinkRepository extends JpaRepository<TelegramLink, Long> {

    Optional<TelegramLink> findByUserId(Long userId);

    Optional<TelegramLink> findByChatId(Long chatId);

    boolean existsByUserId(Long userId);

    void deleteByUserId(Long userId);

    List<TelegramLink> findByUserIdIn(List<Long> userIds);
}
