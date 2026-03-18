package com.nhatnam.server.config;

import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seed 5 xe mẫu nếu bảng pos_stores còn trống.
 * Chạy 1 lần duy nhất khi start app.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PosStoreDataInitializer implements CommandLineRunner {

    private static final String AVATAR =
            "/images/category/category_1772470354945_87d742ff-bf4a-4d0d-bb10-88a385a492f6.png";

    private final PosStoreRepository posStoreRepository;

    @Override
    public void run(String... args) {
        if (posStoreRepository.count() > 0) return;

        List<PosStore> stores = List.of(
                PosStore.builder()
                        .name("Xe Quận 1")
                        .address("123 Lê Lợi, Phường Bến Nghé, Quận 1, TP.HCM")
                        .phone("0901234561")
                        .avatarUrl(AVATAR)
                        .build(),
                PosStore.builder()
                        .name("Xe Quận 3")
                        .address("456 Nguyễn Đình Chiểu, Phường 4, Quận 3, TP.HCM")
                        .phone("0901234562")
                        .avatarUrl(AVATAR)
                        .build(),
                PosStore.builder()
                        .name("Xe Bình Thạnh")
                        .address("789 Đinh Bộ Lĩnh, Phường 26, Quận Bình Thạnh, TP.HCM")
                        .phone("0901234563")
                        .avatarUrl(AVATAR)
                        .build(),
                PosStore.builder()
                        .name("Xe Gò Vấp")
                        .address("12 Quang Trung, Phường 10, Quận Gò Vấp, TP.HCM")
                        .phone("0901234564")
                        .avatarUrl(AVATAR)
                        .build(),
                PosStore.builder()
                        .name("Xe Tân Bình")
                        .address("88 Cộng Hòa, Phường 4, Quận Tân Bình, TP.HCM")
                        .phone("0901234565")
                        .avatarUrl(AVATAR)
                        .build()
        );

        posStoreRepository.saveAll(stores);
        log.info("✅ Seeded {} PosStore records", stores.size());
    }
}