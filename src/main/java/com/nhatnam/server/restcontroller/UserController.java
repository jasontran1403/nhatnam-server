package com.nhatnam.server.restcontroller;

import com.nhatnam.server.config.TransactionLockManager;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/user")
public class UserController {
    private final TransactionLockManager transactionLockManager;

    // Format with milliseconds: HH:mm:ss.SSS
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Request counter để tracking
    private static final AtomicLong requestCounter = new AtomicLong(0);

    @PostMapping("/test-lock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testLock(Authentication authentication) {
        // Tạo request ID
        long requestId = requestCounter.incrementAndGet();
        String timestamp = LocalTime.now().format(TIME_FORMATTER);

        // Check authentication
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized")
            );
        }

        // Extract user from JWT
        User user = (User) authentication.getPrincipal();
        Long userId = user.getId();

        // Try to acquire lock
        ReentrantLock lock = transactionLockManager.getLock(userId);

        if (lock.tryLock()) {
            try {
                log.info("[{}] ✅ REQ-{}: ACQUIRED LOCK - Processing order for userId: {}",
                        timestamp, requestId, userId);

                // Giả lập việc tạo đơn hàng - chờ 5 giây
                Thread.sleep(5000);

                String doneTimestamp = LocalTime.now().format(TIME_FORMATTER);
                log.info("[{}] ✅ REQ-{}: DONE - Order created successfully for userId: {}",
                        doneTimestamp, requestId, userId);

                // Prepare response data
                Map<String, Object> data = new HashMap<>();
                data.put("userId", userId);
                data.put("username", user.getUsername());
                data.put("requestId", requestId);
                data.put("processTime", "5 seconds");

                return ResponseEntity.ok(
                        ApiResponse.success(StatusCode.SUCCESS, data, "Order created successfully")
                );

            } catch (InterruptedException e) {
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
            }
        } else {
            // Lock không lấy được - có transaction khác đang chạy
            log.warn("[{}] ⛔ REQ-{}: DENIED - Lock denied for userId: {}",
                    timestamp, requestId, userId);

            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.TOO_MANY_REQUESTS,
                            "Another transaction is being processed. Please wait and try again."
                    )
            );
        }
    }
}