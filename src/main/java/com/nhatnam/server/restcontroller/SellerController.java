package com.nhatnam.server.restcontroller;

import com.nhatnam.server.config.TransactionLockManager;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/seller")
public class SellerController {
    private final TransactionLockManager transactionLockManager;

    @PostMapping("/test-lock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testLock(Authentication authentication) {
        // Check authentication
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("[TEST-LOCK] Unauthorized access attempt");
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized")
            );
        }

        // Extract user from JWT
        User user = (User) authentication.getPrincipal();
        Long userId = user.getId();

        log.info("[TEST-LOCK] Request from userId: {}, username: {}", userId, user.getUsername());

        // Try to acquire lock
        ReentrantLock lock = transactionLockManager.getLock(userId);

        if (lock.tryLock()) {
            try {
                log.info("[TEST-LOCK] Lock acquired for userId: {}", userId);

                // Giả lập việc tạo đơn hàng - chờ 5 giây
                Thread.sleep(5000);

                log.info("[TEST-LOCK] Done - Order created successfully for userId: {}", userId);

                // Prepare response data
                Map<String, Object> data = new HashMap<>();
                data.put("userId", userId);
                data.put("username", user.getUsername());
                data.put("processTime", "5 seconds");

                return ResponseEntity.ok(
                        ApiResponse.success(StatusCode.SUCCESS, data, "Order created successfully")
                );

            } catch (InterruptedException e) {
                log.error("[TEST-LOCK] Thread interrupted for userId: {}", userId, e);
                Thread.currentThread().interrupt();
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Process interrupted")
                );
            } catch (Exception e) {
                log.error("[TEST-LOCK] Unexpected error for userId: {}", userId, e);
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Order creation failed")
                );
            } finally {
                lock.unlock();
                transactionLockManager.cleanupIfUnused(userId);
                log.info("[TEST-LOCK] Lock released for userId: {}", userId);
            }
        } else {
            // Lock không lấy được - có transaction khác đang chạy
            log.warn("[TEST-LOCK] Lock denied - Another transaction in progress for userId: {}", userId);
            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.TOO_MANY_REQUESTS,
                            "Another transaction is being processed. Please wait and try again."
                    )
            );
        }
    }
}