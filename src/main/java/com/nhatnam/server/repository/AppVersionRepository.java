package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {
    Optional<AppVersion> findByPlatform(String platform);
}