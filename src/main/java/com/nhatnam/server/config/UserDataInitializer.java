package com.nhatnam.server.config;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;

/**
 * Seed 8 user ban đầu khi DB mới.
 * Password đã được hash sẵn bằng BCrypt — KHÔNG hash lại.
 * Secret MFA được generate tự động bằng SecretGenerator.
 */
@Log4j2
@Component
@Order(1)
@RequiredArgsConstructor
public class UserDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("[UserInit] Users already exist, skipping.");
            return;
        }

        userRepository.save(User.builder()
                .email("seller1@gmail.com")
                .fullName("Seller1")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$8ey6vBMRoXd6kt4BkNtQZ.vMOi99.KQaAK4M7/cCFa/j8Udb2C6rG")
                .phoneNumber("+84938121001")
                .role(Role.SELLER)
                .secret(secretGenerator.generate())
                .timeCreate(1769017484747L)
                .username("seller1")
                .build());

        userRepository.save(User.builder()
                .email("seller2@gmail.com")
                .fullName("seller2")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$sf4ZZC3AhkYHlWEFLiYiP.FDSFZ0bfbEN7JJH.bU5ETAY1whzU7m.")
                .phoneNumber("+84934513968")
                .role(Role.SELLER)
                .secret(secretGenerator.generate())
                .timeCreate(1771772100019L)
                .username("Seller2")
                .build());

        userRepository.save(User.builder()
                .email("447huynhvanbanh@gmail.com")
                .fullName("447 Huỳnh Văn Bánh")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$bXqEUs39qTrFck24oL/cku2cAl0pQVldgIwXdCYdkddI/hOGnptVq")
                .phoneNumber("0934513968")
                .role(Role.POS)
                .secret(secretGenerator.generate())
                .timeCreate(1771836961351L)
                .username("447hvb")
                .build());

        userRepository.save(User.builder()
                .email("monu@gmail.com")
                .fullName("OT MON Ú")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$bXqEUs39qTrFck24oL/cku2cAl0pQVldgIwXdCYdkddI/hOGnptVq")
                .phoneNumber("0934513968")
                .role(Role.POS)
                .secret(secretGenerator.generate())
                .timeCreate(1771836961351L)
                .username("99ntb")
                .build());

        userRepository.save(User.builder()
                .email("Mr.long@gmail.com")
                .fullName("Phan Nguyễn Hoàng Long")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$Mzm4U/pWD0ts6WvF5ABgd.k10ztYHd1k6tH7Fc5FdAz7xcW4TqQp6")
                .phoneNumber("0934889079")
                .role(Role.ADMIN)
                .secret(secretGenerator.generate())
                .timeCreate(1771838841030L)
                .username("admin1")
                .build());

        userRepository.save(User.builder()
                .email("nguyenhai@gmail.com")
                .fullName("Trần Nguyên Hải")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$O1Vebu0hbNCUn6t4xit8zuAsQseI41XvhzVPI3pBLcq3t607M1hKu")
                .phoneNumber("0938121001")
                .role(Role.ADMIN)
                .secret(secretGenerator.generate())
                .timeCreate(1771840031951L)
                .username("admin2")
                .build());

        userRepository.save(User.builder()
                .email("sjsjsjsh@gmail.com")
                .fullName("Test")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$bXqEUs39qTrFck24oL/cku2cAl0pQVldgIwXdCYdkddI/hOGnptVq")
                .phoneNumber("+84989204057")
                .role(Role.POS)
                .secret(secretGenerator.generate())
                .timeCreate(1772105069143L)
                .username("test")
                .build());

        userRepository.save(User.builder()
                .email("sonlamno2game@gmail.com")
                .fullName("Lam Nguyen")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$D4/WWehIg8ghrPxHC0h2du1T0Q0J/B0aHJUTAleyQjIL/B7/gT2Ea")
                .phoneNumber("0934889079")
                .role(Role.SUPERADMIN)
                .secret(secretGenerator.generate())
                .timeCreate(1771838841030L)
                .username("lamnguyen")
                .build());

        userRepository.save(User.builder()
                .email("Mr.long@gmail.com")
                .fullName("Long Phan")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$Mzm4U/pWD0ts6WvF5ABgd.k10ztYHd1k6tH7Fc5FdAz7xcW4TqQp6")
                .phoneNumber("0934889079")
                .role(Role.SUPERADMIN)
                .secret(secretGenerator.generate())
                .timeCreate(1771838841030L)
                .username("longphan")
                .build());

        log.info("[UserInit] Seeded 8 users successfully.");
    }
}