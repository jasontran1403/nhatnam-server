package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.entity.TodoTask;
import com.nhatnam.server.tools.vmb.service.TodoTaskService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints Todo Task.
 *
 *   GET    /api/tools/vmb/todo?from=&to=      — list trong khoảng
 *   POST   /api/tools/vmb/todo                — tạo task (JSON)
 *   PUT    /api/tools/vmb/todo/{id}           — sửa task (JSON)
 *   DELETE /api/tools/vmb/todo/{id}
 *   POST   /api/tools/vmb/todo/{id}/complete  — multipart {file, note}
 *   POST   /api/tools/vmb/todo/{id}/extend    — JSON {deadline, note}
 *   POST   /api/tools/vmb/todo/{id}/cancel    — JSON {note}
 *   GET    /api/tools/vmb/todo/stats/today    — { pendingCount, nearestDeadline }
 */
@RestController
@RequestMapping("/api/tools/vmb/todo")
@RequiredArgsConstructor
@Log4j2
public class TodoTaskController {

    private static final String FILE_PREFIX = "/vmb-files/";

    private final TodoTaskService service;

    // ── LIST ─────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<List<TodoTaskIO>>> list(
            @RequestParam Long from, @RequestParam Long to) {
        try {
            var list = service.listByDeadlineRange(from, to).stream().map(TodoTaskController::toIO).toList();
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            log.error("[Todo] list", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách task."));
        }
    }

    @GetMapping("/stats/today")
    public ResponseEntity<ApiResponse<StatsToday>> statsToday() {
        try {
            var zone = ZoneId.systemDefault();
            var start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
            var end   = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            var pending = service.listPendingInRange(start, end);
            Long nearest = pending.stream().mapToLong(TodoTask::getDeadline).min().orElse(0);
            return ResponseEntity.ok(ApiResponse.success(
                    StatsToday.builder().pendingCount(pending.size()).nearestDeadline(nearest > 0 ? nearest : null).build(),
                    "OK"));
        } catch (Exception e) {
            log.error("[Todo] stats today", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tính được stats."));
        }
    }

    // ── CREATE / UPDATE / DELETE ─────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<TodoTaskIO>> create(@RequestBody CreateReq req) {
        try {
            var t = service.create(req.getUnitName(), req.getDescription(), req.getDeadline(), req.getNote());
            return ResponseEntity.ok(ApiResponse.success(toIO(t), "Đã tạo task"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] create", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo task thất bại."));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TodoTaskIO>> update(@PathVariable Long id, @RequestBody CreateReq req) {
        try {
            var t = service.update(id, req.getUnitName(), req.getDescription(), req.getDeadline(), req.getNote());
            return ResponseEntity.ok(ApiResponse.success(toIO(t), "Đã cập nhật task"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] update {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (Exception e) {
            log.error("[Todo] delete {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    // ── TRANSITIONS ──────────────────────────────────

    @PostMapping(value = "/{id}/complete", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<TodoTaskIO>> complete(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "note", required = false) String note) {
        try {
            return ResponseEntity.ok(ApiResponse.success(toIO(service.complete(id, file, note)), "Đã hoàn thành task"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] complete {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Hoàn thành task thất bại."));
        }
    }

    @PostMapping("/{id}/extend")
    public ResponseEntity<ApiResponse<TodoTaskIO>> extend(@PathVariable Long id, @RequestBody ExtendReq req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(toIO(service.extend(id, req.getDeadline(), req.getNote())), "Đã gia hạn"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] extend {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Gia hạn thất bại."));
        }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<TodoTaskIO>> cancel(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(toIO(service.cancel(id, body.get("note"))), "Đã hủy task"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] cancel {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Hủy task thất bại."));
        }
    }

    // ── DTOs ────────────────────────────────────────

    private static TodoTaskIO toIO(TodoTask t) {
        return TodoTaskIO.builder()
                .id(t.getId())
                .unitName(t.getUnitName())
                .description(t.getDescription())
                .deadline(t.getDeadline())
                .status(t.getStatus())
                .confirmationUrl(t.getConfirmationFile() == null ? null : FILE_PREFIX + t.getConfirmationFile())
                .confirmationOriginal(t.getConfirmationOriginal())
                .note(t.getNote())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    @Getter @Setter
    public static class CreateReq {
        private String unitName;
        private String description;
        private Long deadline;
        private String note;
    }

    @Getter @Setter
    public static class ExtendReq {
        private Long deadline;
        private String note;
    }

    @Getter @Setter @lombok.NoArgsConstructor @lombok.AllArgsConstructor @lombok.Builder
    public static class TodoTaskIO {
        private Long id;
        private String unitName;
        private String description;
        private Long deadline;
        private String status;
        private String confirmationUrl;
        private String confirmationOriginal;
        private String note;
        private Long createdAt;
        private Long updatedAt;
    }

    @Getter @Setter @lombok.NoArgsConstructor @lombok.AllArgsConstructor @lombok.Builder
    public static class StatsToday {
        private Integer pendingCount;
        private Long nearestDeadline;
    }
}