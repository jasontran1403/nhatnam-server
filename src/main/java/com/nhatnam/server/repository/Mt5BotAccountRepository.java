package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Mt5BotAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface Mt5BotAccountRepository extends JpaRepository<Mt5BotAccountEntity, Long> {
    Optional<Mt5BotAccountEntity> findByLoginAndServer(String login, String server);

    List<Mt5BotAccountEntity> findAllByOrderByCreatedAtAsc();
}
