package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Token;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TokenRepository extends JpaRepository<Token, Integer> {
    @Query(value = """
        SELECT t FROM Token t 
        INNER JOIN User u ON t.user.id = u.id
        WHERE u.id = :id AND (t.expired = false OR t.revoked = false)
        """)
    List<Token> findAllValidTokenByUser(@Param("id") Long id);

    /**
     * Tìm token theo giá trị token
     */
    Optional<Token> findByToken(String token);
}
