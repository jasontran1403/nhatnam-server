package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.TaskDto;
import com.nhatnam.server.dto.TaskDto.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.DeadlineExtension;
import com.nhatnam.server.entity.TaskMessage;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    // ═══════════ ADMIN / SUPERADMIN ═══════════════════════════════

    @PostMapping("/api/admin/tasks")
    public ApiResponse<TaskResponse> createTask(
            @RequestBody CreateTaskRequest req, @AuthenticationPrincipal User admin) {
        return ApiResponse.success(taskService.createTask(req, admin, "ADMIN"), "Tạo task thành công");
    }

    @PutMapping("/api/admin/tasks/{id}")
    public ApiResponse<TaskResponse> updateTask(
            @PathVariable Long id, @RequestBody UpdateTaskRequest req,
            @AuthenticationPrincipal User admin) {
        return ApiResponse.success(taskService.updateTask(id, req, admin), "Cập nhật thành công");
    }

    @DeleteMapping("/api/admin/tasks/{id}")
    public ApiResponse<Void> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ApiResponse.success(null, "Đã xóa task");
    }

    /** Admin list — chỉ task ADMIN type */
    @GetMapping("/api/admin/tasks")
    public ApiResponse<TaskPage> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        return ApiResponse.success(
                taskService.findAdminTasks(status, priority, category, assigneeId, q, from, to, page, size), "OK");
    }

    @GetMapping("/api/admin/tasks/{id}")
    public ApiResponse<TaskResponse> getTask(@PathVariable Long id) {
        return ApiResponse.success(taskService.getTaskDetail(id), "OK");
    }

    /** Dashboard — chỉ thống kê task ADMIN type */
    @GetMapping("/api/admin/tasks/dashboard")
    public ApiResponse<DashboardStats> dashboard() {
        return ApiResponse.success(taskService.getDashboardStats(), "OK");
    }

    @GetMapping("/api/admin/tasks/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.success(taskService.getCategories(), "OK");
    }

    @GetMapping("/api/admin/tasks/users")
    public ApiResponse<List<TaskDto.UserSimple>> assignableUsers() {
        return ApiResponse.success(taskService.getAssignableUsers(), "OK");
    }

    /** Admin gán thêm / đổi người xử lý (chỉ cho task ADMIN, không phải PERSONAL) */
    @PutMapping("/api/admin/tasks/{id}/reassign")
    public ApiResponse<TaskResponse> reassignTask(
            @PathVariable Long id, @RequestBody ReassignRequest req,
            @AuthenticationPrincipal User admin) {
        return ApiResponse.success(taskService.reassignTask(id, req, admin), "Đã cập nhật người xử lý");
    }

    @GetMapping("/api/admin/tasks/extensions")
    public ApiResponse<Page<DeadlineExtension>> pendingExtensions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(taskService.getPendingExtensions(page, size), "OK");
    }

    @PutMapping("/api/admin/tasks/extensions/{id}")
    public ApiResponse<ExtensionResponse> reviewExtension(
            @PathVariable Long id, @RequestBody ReviewExtensionRequest req,
            @AuthenticationPrincipal User admin) {
        return ApiResponse.success(taskService.reviewExtension(id, req, admin), "OK");
    }

    // ═══════════ USER (+ SELLER, POS, ACCOUNTANT) ════════════════

    /** User tạo task cá nhân (PERSONAL) — chỉ mình thấy */
    @PostMapping("/api/user/tasks")
    public ApiResponse<TaskResponse> createPersonalTask(
            @RequestBody CreateTaskRequest req, @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.createTask(req, user, "PERSONAL"), "Tạo task cá nhân thành công");
    }

    /** User list — task ADMIN được gán + task PERSONAL do mình tạo */
    @GetMapping("/api/user/tasks")
    public ApiResponse<TaskPage> userTasks(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        return ApiResponse.success(
                taskService.findUserTasks(user.getId(), status, priority, category, q, from, to, page, size), "OK");
    }

    @GetMapping("/api/user/tasks/{id}")
    public ApiResponse<TaskResponse> userTaskDetail(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.getTaskDetailForUser(id, user), "OK");
    }

    /** User update task cá nhân (chỉ task PERSONAL do mình tạo) */
    @PutMapping("/api/user/tasks/{id}")
    public ApiResponse<TaskResponse> userUpdateTask(
            @PathVariable Long id, @RequestBody UpdateTaskRequest req,
            @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.updatePersonalTask(id, req, user), "Cập nhật thành công");
    }

    /** User xóa task cá nhân */
    @DeleteMapping("/api/user/tasks/{id}")
    public ApiResponse<Void> userDeleteTask(
            @PathVariable Long id, @AuthenticationPrincipal User user) {
        taskService.deletePersonalTask(id, user);
        return ApiResponse.success(null, "Đã xóa task");
    }

    @PatchMapping("/api/user/tasks/{id}/progress")
    public ApiResponse<TaskResponse> userUpdateProgress(
            @PathVariable Long id, @RequestBody UpdateProgressRequest req,
            @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.updateProgress(id, req, user), "OK");
    }

    /** Hoàn thành 1 đầu mục con */
    @PostMapping("/api/user/tasks/{taskId}/sub/{subId}/complete")
    public ApiResponse<TaskResponse> userCompleteSubItem(
            @PathVariable Long taskId, @PathVariable Long subId,
            @RequestBody(required = false) CompleteSubItemRequest req,
            @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.completeSubItem(taskId, subId, req, user), "OK");
    }

    @PostMapping("/api/user/tasks/{id}/complete")
    public ApiResponse<TaskResponse> userComplete(
            @PathVariable Long id, @RequestBody CompleteTaskRequest req,
            @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.completeTask(id, req, user), "OK");
    }

    @PostMapping("/api/user/tasks/{id}/extension")
    public ApiResponse<ExtensionResponse> userExtension(
            @PathVariable Long id, @RequestBody ExtensionRequest req,
            @AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.requestExtension(id, req, user), "OK");
    }

    // ═══════════ Messages (tất cả role) ══════════════════════════

    @GetMapping("/api/user/messages")
    public ApiResponse<Page<TaskMessage>> userMessages(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(taskService.getMessages(user.getId(), page, size), "OK");
    }

    @GetMapping("/api/user/messages/unread")
    public ApiResponse<Long> userUnread(@AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.getUnreadCount(user.getId()), "OK");
    }

    @PatchMapping("/api/user/messages/{id}/read")
    public ApiResponse<Void> userMarkRead(@PathVariable Long id, @AuthenticationPrincipal User user) {
        taskService.markRead(id, user.getId());
        return ApiResponse.success(null, "OK");
    }

    @PostMapping("/api/user/messages/read-all")
    public ApiResponse<Void> userMarkAllRead(@AuthenticationPrincipal User user) {
        taskService.markAllRead(user.getId());
        return ApiResponse.success(null, "OK");
    }

    @GetMapping("/api/admin/messages")
    public ApiResponse<Page<TaskMessage>> adminMessages(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(taskService.getMessages(user.getId(), page, size), "OK");
    }

    @GetMapping("/api/admin/messages/unread")
    public ApiResponse<Long> adminUnread(@AuthenticationPrincipal User user) {
        return ApiResponse.success(taskService.getUnreadCount(user.getId()), "OK");
    }

    @PatchMapping("/api/admin/messages/{id}/read")
    public ApiResponse<Void> adminMarkRead(@PathVariable Long id, @AuthenticationPrincipal User user) {
        taskService.markRead(id, user.getId());
        return ApiResponse.success(null, "OK");
    }

    @PostMapping("/api/admin/messages/read-all")
    public ApiResponse<Void> adminMarkAllRead(@AuthenticationPrincipal User user) {
        taskService.markAllRead(user.getId());
        return ApiResponse.success(null, "OK");
    }
}
