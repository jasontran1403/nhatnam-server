package com.nhatnam.server.repository.pos;
import com.nhatnam.server.entity.pos.PosAppMenu;
import com.nhatnam.server.entity.pos.PosProduct;
import com.nhatnam.server.enumtype.AppPlatform;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface PosAppMenuRepository extends JpaRepository<PosAppMenu, Long> {
    Optional<PosAppMenu> findByProductAndPlatform(PosProduct product, AppPlatform platform);
}