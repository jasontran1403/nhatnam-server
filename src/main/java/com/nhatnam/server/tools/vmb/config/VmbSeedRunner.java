package com.nhatnam.server.tools.vmb.config;

import com.nhatnam.server.tools.vmb.entity.Company;
import com.nhatnam.server.tools.vmb.repository.CompanyRepository;
import com.nhatnam.server.tools.vmb.service.PassengerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đảm bảo lúc khởi động luôn có Company "Khách lẻ" — để mọi Passenger đều có
 * FK company hợp lệ dù người dùng bỏ trống ô công ty.
 *
 * Idempotent: không làm gì nếu đã tồn tại.
 */
@Configuration
@RequiredArgsConstructor
@Log4j2
public class VmbSeedRunner {

    @Bean
    public ApplicationRunner vmbSeed(CompanyRepository repo) {
        return args -> seedDefault(repo);
    }

    @Transactional
    protected void seedDefault(CompanyRepository repo) {
        String name = PassengerService.DEFAULT_COMPANY_NAME;
        if (repo.findByNameIgnoreCase(name).isEmpty()) {
            repo.save(Company.builder()
                    .name(name)
                    .createdAt(System.currentTimeMillis())
                    .build());
            log.info("[VMB][Seed] Đã tạo công ty mặc định '{}'", name);
        }
    }
}
