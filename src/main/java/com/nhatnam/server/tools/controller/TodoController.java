package com.nhatnam.server.tools.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.ToolsAuthContext;
import com.nhatnam.server.tools.entity.TodoItem;
import com.nhatnam.server.tools.repository.TodoItemRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD cho Todo.
 * Base path: /api/tools/todo — nằm trong PROTECTED_PREFIXES của ToolsAuthFilter,
 * mọi thao tác đã có username; controller chỉ cần dán owner và enforce ownership.
 *
 * KHÔNG dùng {@code creator} (chuỗi hiển thị, có thể trùng nhau, có dấu) để
 * kiểm tra sở hữu — dùng {@code owner} (chính là username).
 */
@RestController
@RequestMapping("/api/tools/todo")
@RequiredArgsConstructor
@Log4j2
public class TodoController {

    private final TodoItemRepository repo;

    /** GET — danh sách CỦA USER hiện tại, lọc linh hoạt */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String creator,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        try {
            String owner = ToolsAuthContext.username(request);
            var paged = repo.search(
                    owner,
                    status != null && !status.isBlank() ? status.trim() : null,
                    creator != null && !creator.isBlank() ? creator.trim() : null,
                    q != null && !q.isBlank() ? q.trim() : null,
                    from, to,
                    PageRequest.of(page, Math.min(size, 200)));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", paged.getContent().stream().map(TodoController::toMap).toList());
            result.put("totalElements", paged.getTotalElements());
            result.put("totalPages", paged.getTotalPages());
            result.put("currentPage", paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[Todo] list error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách todo."));
        }
    }

    /** POST — tạo mới, owner tự lấy từ token; creator (tên hiển thị) client vẫn được đặt */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            String content = String.valueOf(body.getOrDefault("content", ""));
            String creator = String.valueOf(body.getOrDefault("creator", ""));
            Long dueAt = body.get("dueAt") != null ? Long.parseLong(String.valueOf(body.get("dueAt"))) : null;

            if (content.isBlank()) return ResponseEntity.ok(ApiResponse.error(400, "Nội dung không được để trống."));
            // Nếu client không gửi creator (tên hiển thị), dùng username làm mặc định
            if (creator.isBlank()) creator = owner;

            long now = System.currentTimeMillis();
            TodoItem item = TodoItem.builder()
                    .content(content.trim())
                    .creator(creator.trim())
                    .owner(owner)
                    .dueAt(dueAt)
                    .status("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            return ResponseEntity.ok(ApiResponse.success(toMap(repo.save(item)), "Đã tạo todo"));
        } catch (Exception e) {
            log.error("[Todo] create error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo todo thất bại."));
        }
    }

    /** Kiểm ownership rồi trả entity — hoặc ném "Không tìm thấy" nếu là của người khác */
    private TodoItem findOwned(Long id, String owner) {
        TodoItem t = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy todo."));
        if (t.getOwner() != null && !t.getOwner().equals(owner)) {
            throw new ToolsException("Không tìm thấy todo.");
        }
        return t;
    }

    /** PATCH /{id}/status — đổi trạng thái */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(
            HttpServletRequest request,
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            TodoItem item = findOwned(id, owner);

            String newStatus = body.getOrDefault("status", "");
            if (!List.of("PENDING", "IN_PROGRESS", "DONE", "CANCELLED").contains(newStatus)) {
                return ResponseEntity.ok(ApiResponse.error(400, "Trạng thái không hợp lệ."));
            }

            item.setStatus(newStatus);
            item.setUpdatedAt(System.currentTimeMillis());
            return ResponseEntity.ok(ApiResponse.success(toMap(repo.save(item)), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] updateStatus error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    /** PATCH /{id} — sửa nội dung, thời hạn, tên hiển thị */
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            HttpServletRequest request,
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            TodoItem item = findOwned(id, owner);

            if (body.containsKey("content")) {
                String c = String.valueOf(body.get("content")).trim();
                if (!c.isBlank()) item.setContent(c);
            }
            if (body.containsKey("dueAt")) {
                item.setDueAt(body.get("dueAt") != null ? Long.parseLong(String.valueOf(body.get("dueAt"))) : null);
            }
            if (body.containsKey("creator")) {
                String cr = String.valueOf(body.get("creator")).trim();
                if (!cr.isBlank()) item.setCreator(cr);
            }

            item.setUpdatedAt(System.currentTimeMillis());
            return ResponseEntity.ok(ApiResponse.success(toMap(repo.save(item)), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] update error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    /** DELETE /{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> delete(HttpServletRequest request, @PathVariable Long id) {
        try {
            String owner = ToolsAuthContext.username(request);
            findOwned(id, owner);   // ném lỗi nếu không thuộc user
            repo.deleteById(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        } catch (Exception e) {
            log.error("[Todo] delete error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }

    private static Map<String, Object> toMap(TodoItem t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("content", t.getContent());
        m.put("creator", t.getCreator());
        m.put("dueAt", t.getDueAt());
        m.put("status", t.getStatus());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }
}
