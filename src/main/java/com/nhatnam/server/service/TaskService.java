package com.nhatnam.server.service;

import com.nhatnam.server.dto.TaskDto;
import com.nhatnam.server.dto.TaskDto.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepo;
    private final TaskAssignmentRepository assignmentRepo;
    private final TaskProgressLogRepository progressRepo;
    private final TaskMessageRepository messageRepo;
    private final DeadlineExtensionRepository extensionRepo;
    private final TaskSubItemRepository subItemRepo;
    private final UserRepository userRepo;
    private final TelegramNotifier telegram;

    // ─── Create Task (ADMIN hoặc PERSONAL) ──────────────────────

    @Transactional
    public TaskResponse createTask(CreateTaskRequest req, User creator, String taskType) {
        long now = System.currentTimeMillis();
        Task task = Task.builder()
                .title(req.getTitle()).description(req.getDescription()).requirements(req.getRequirements())
                .priority(req.getPriority() != null ? req.getPriority() : "MEDIUM")
                .category(req.getCategory()).deadline(req.getDeadline())
                .sequentialSubtasks(req.getSequentialSubtasks() != null && req.getSequentialSubtasks())
                .taskType(taskType)
                .createdBy(creator.getId()).createdByName(dn(creator)).createdAt(now).updatedAt(now).build();
        task = taskRepo.save(task);
        if (req.getSubItems() != null && !req.getSubItems().isEmpty()) saveSubItems(task.getId(), req.getSubItems());

        if ("PERSONAL".equals(taskType)) {
            // Task cá nhân: tự gán cho chính mình
            assignmentRepo.save(TaskAssignment.builder().taskId(task.getId()).userId(creator.getId())
                    .username(creator.getUsername()).fullName(creator.getFullName()).assignedAt(now).build());
        } else {
            // Task admin: gán cho người được chọn
            if (req.getAssigneeIds() != null && !req.getAssigneeIds().isEmpty())
                assignUsers(task, req.getAssigneeIds(), creator);
        }
        return toResponse(task);
    }

    // ─── Update Task (Admin) ────────────────────────────────────

    @Transactional
    public TaskResponse updateTask(Long taskId, UpdateTaskRequest req, User admin) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Task not found"));
        if (req.getTitle() != null) task.setTitle(req.getTitle());
        if (req.getDescription() != null) task.setDescription(req.getDescription());
        if (req.getRequirements() != null) task.setRequirements(req.getRequirements());
        if (req.getPriority() != null) task.setPriority(req.getPriority());
        if (req.getCategory() != null) task.setCategory(req.getCategory());
        if (req.getDeadline() != null) task.setDeadline(req.getDeadline());
        if (req.getSequentialSubtasks() != null) task.setSequentialSubtasks(req.getSequentialSubtasks());
        if (req.getStatus() != null && !req.getStatus().equals(task.getStatus())) {
            String old = task.getStatus(); task.setStatus(req.getStatus());
            if ("PAUSED".equals(req.getStatus())) {
                notifyAll(task, admin, "TASK_PAUSED", "Task \"" + task.getTitle() + "\" đã bị tạm dừng");
                progressRepo.save(TaskProgressLog.builder().taskId(taskId).userId(admin.getId()).username(dn(admin))
                        .progress(task.getProgress()).note("Tạm dừng task").createdAt(System.currentTimeMillis()).build());
            } else if ("PAUSED".equals(old)) {
                notifyAll(task, admin, "TASK_RESUMED", "Task \"" + task.getTitle() + "\" đã được mở lại");
                progressRepo.save(TaskProgressLog.builder().taskId(taskId).userId(admin.getId()).username(dn(admin))
                        .progress(task.getProgress()).note("Mở lại task").createdAt(System.currentTimeMillis()).build());
            }
        }
        task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
        if (req.getSubItems() != null) { subItemRepo.deleteByTaskId(taskId); saveSubItems(taskId, req.getSubItems()); recalc(task); }
        if (req.getAssigneeIds() != null && "ADMIN".equals(task.getTaskType())) {
            List<TaskAssignment> ex = assignmentRepo.findByTaskId(taskId);
            Set<Long> exIds = ex.stream().map(TaskAssignment::getUserId).collect(Collectors.toSet());
            Set<Long> newIds = new HashSet<>(req.getAssigneeIds());
            ex.stream().filter(a -> !newIds.contains(a.getUserId())).forEach(assignmentRepo::delete);
            assignUsers(task, newIds.stream().filter(id -> !exIds.contains(id)).toList(), admin);
        }
        return toResponse(task);
    }

    // ─── Update Personal Task (User) ────────────────────────────

    @Transactional
    public TaskResponse updatePersonalTask(Long taskId, UpdateTaskRequest req, User user) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Task not found"));
        if (!"PERSONAL".equals(task.getTaskType()) || !task.getCreatedBy().equals(user.getId()))
            throw new RuntimeException("Chỉ được sửa task cá nhân do bạn tạo");
        if (req.getTitle() != null) task.setTitle(req.getTitle());
        if (req.getDescription() != null) task.setDescription(req.getDescription());
        if (req.getRequirements() != null) task.setRequirements(req.getRequirements());
        if (req.getPriority() != null) task.setPriority(req.getPriority());
        if (req.getCategory() != null) task.setCategory(req.getCategory());
        if (req.getDeadline() != null) task.setDeadline(req.getDeadline());
        if (req.getSequentialSubtasks() != null) task.setSequentialSubtasks(req.getSequentialSubtasks());
        if (req.getStatus() != null) task.setStatus(req.getStatus());
        task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
        if (req.getSubItems() != null) { subItemRepo.deleteByTaskId(taskId); saveSubItems(taskId, req.getSubItems()); recalc(task); }
        return toResponse(task);
    }

    // ─── Delete ─────────────────────────────────────────────────

    @Transactional
    public void deleteTask(Long id) { subItemRepo.deleteByTaskId(id); assignmentRepo.deleteByTaskId(id); taskRepo.deleteById(id); }

    @Transactional
    public void deletePersonalTask(Long id, User user) {
        Task task = taskRepo.findById(id).orElseThrow(() -> new RuntimeException("Task not found"));
        if (!"PERSONAL".equals(task.getTaskType()) || !task.getCreatedBy().equals(user.getId()))
            throw new RuntimeException("Chỉ được xóa task cá nhân do bạn tạo");
        subItemRepo.deleteByTaskId(id); assignmentRepo.deleteByTaskId(id); taskRepo.deleteById(id);
    }

    // ─── Reassign (Admin only, chỉ task ADMIN) ──────────────────

    @Transactional
    public TaskResponse reassignTask(Long taskId, ReassignRequest req, User admin) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Task not found"));
        if (!"ADMIN".equals(task.getTaskType()))
            throw new RuntimeException("Không thể đổi người cho task cá nhân");

        List<TaskAssignment> existing = assignmentRepo.findByTaskId(taskId);
        Set<Long> oldIds = existing.stream().map(TaskAssignment::getUserId).collect(Collectors.toSet());
        Set<Long> newIds = new HashSet<>(req.getAssigneeIds());

        // Xóa người bị loại
        existing.stream().filter(a -> !newIds.contains(a.getUserId())).forEach(a -> {
            assignmentRepo.delete(a);
            msg(a.getUserId(), admin, task, "UNASSIGNED", "Bạn đã được gỡ khỏi task: \"" + task.getTitle() + "\"");
        });

        // Thêm người mới
        assignUsers(task, newIds.stream().filter(id -> !oldIds.contains(id)).toList(), admin);

        task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
        return toResponse(task);
    }

    // ─── Subtask completion ──────────────────────────────────────

    @Transactional
    public TaskResponse completeSubItem(Long taskId, Long subId, CompleteSubItemRequest req, User user) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Task not found"));
        guardCanWork(task, user); guardNotPaused(task);
        TaskSubItem sub = subItemRepo.findById(subId).orElseThrow(() -> new RuntimeException("Sub not found"));
        if (!sub.getTaskId().equals(taskId)) throw new RuntimeException("Mismatch");
        if (sub.getCompleted()) throw new RuntimeException("Already done");

        // Kiểm tra sub-item có gán cho người cụ thể không
        if (sub.getAssigneeId() != null && !sub.getAssigneeId().equals(user.getId())) {
            throw new RuntimeException("Đầu mục này được gán cho " + (sub.getAssigneeName() != null ? sub.getAssigneeName() : "người khác"));
        }

        if (task.getSequentialSubtasks()) {
            for (TaskSubItem s : subItemRepo.findByTaskIdOrderByOrderIndexAsc(taskId)) {
                if (s.getId().equals(subId)) break;
                if (!s.getCompleted()) throw new RuntimeException("Phải hoàn thành theo thứ tự");
            }
        }
        sub.setCompleted(true); sub.setCompletedBy(user.getId()); sub.setCompletedByName(dn(user));
        sub.setCompletedAt(System.currentTimeMillis()); sub.setCompletionNote(req != null ? req.getNote() : null);
        subItemRepo.save(sub); recalc(task);
        progressRepo.save(TaskProgressLog.builder().taskId(taskId).userId(user.getId()).username(dn(user))
                .progress(task.getProgress()).note("Hoàn thành: " + sub.getTitle()).createdAt(System.currentTimeMillis()).build());
        if ("ADMIN".equals(task.getTaskType())) {
            msg(task.getCreatedBy(), user, task, "PROGRESS_UPDATE",
                    dn(user) + " hoàn thành \"" + sub.getTitle() + "\" — " + task.getProgress() + "%");

            // Noti Telegram cho người tạo task (admin)
            telegram.notifyUser(task.getCreatedBy(),
                    "✅ <b>Đầu mục hoàn thành</b>\n" +
                    "• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                    "• Đầu mục: " + TelegramNotifier.esc(sub.getTitle()) + "\n" +
                    "• Tiến độ: <b>" + task.getProgress() + "%</b>\n" +
                    "• Người thực hiện: " + TelegramNotifier.esc(dn(user)));

            // Nếu đầu mục vừa xong là đầu mục cuối → recalc() đã set task = COMPLETED.
            // completeTask() KHÔNG được gọi ở flow này, nên phải bắn noti "task hoàn thành" ngay đây.
            if ("COMPLETED".equals(task.getStatus())) {
                msg(task.getCreatedBy(), user, task, "TASK_COMPLETED",
                        dn(user) + " hoàn thành \"" + task.getTitle() + "\"");
                telegram.notifyUser(task.getCreatedBy(),
                        "🎉 <b>Task đã hoàn thành</b>\n" +
                        "• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                        "• Người hoàn thành: " + TelegramNotifier.esc(dn(user)) + "\n" +
                        "• Hoàn tất bằng việc xong đầu mục cuối cùng.");
            }
        }
        return toResponse(task);
    }

    @Transactional
    public TaskResponse updateProgress(Long taskId, UpdateProgressRequest req, User user) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Not found"));
        guardCanWork(task, user); guardNotPaused(task);

        // Task đã hoàn thành → không cho cập nhật tiếp
        if ("COMPLETED".equals(task.getStatus())) {
            throw new RuntimeException("Task đã hoàn thành, không thể cập nhật tiến độ nữa");
        }

        boolean autoCompleted = false;
        if (req.getProgress() != null) {
            int oldProgress = task.getProgress() != null ? task.getProgress() : 0;
            int newProgress = req.getProgress();

            // Không cho giảm tiến độ
            if (newProgress < oldProgress) {
                throw new RuntimeException(
                        "Không thể giảm tiến độ. Tiến độ hiện tại là " + oldProgress + "%, " +
                        "chỉ có thể cập nhật lên " + oldProgress + "% – 100%.");
            }
            if (newProgress > 100) newProgress = 100;

            task.setProgress(newProgress);
            if (newProgress > 0 && "NOT_STARTED".equals(task.getStatus())) task.setStatus("IN_PROGRESS");

            // Đạt 100% → tự động hoàn thành
            if (newProgress >= 100) {
                task.setStatus("COMPLETED");
                autoCompleted = true;
            }
        }
        task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
        progressRepo.save(TaskProgressLog.builder().taskId(taskId).userId(user.getId()).username(dn(user))
                .progress(req.getProgress() != null ? req.getProgress() : task.getProgress())
                .note(req.getNote()).attachments(req.getAttachments()).createdAt(System.currentTimeMillis()).build());

        if ("ADMIN".equals(task.getTaskType())) {
            msg(task.getCreatedBy(), user, task, "PROGRESS_UPDATE",
                    dn(user) + " cập nhật \"" + task.getTitle() + "\" → " + task.getProgress() + "%");

            // Noti Telegram cho người tạo task (admin) — cập nhật tiến độ
            if (task.getProgress() < 100) {
                telegram.notifyUser(task.getCreatedBy(),
                        "📊 <b>Cập nhật tiến độ</b>\n" +
                                "• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                                "• Tiến độ mới: <b>" + task.getProgress() + "%</b>\n" +
                                "• Người cập nhật: " + TelegramNotifier.esc(dn(user)) +
                                (req.getNote() != null && !req.getNote().isBlank()
                                        ? "\n• Ghi chú: " + TelegramNotifier.esc(req.getNote()) : ""));
            }

            // Nếu vừa đạt 100% và bị auto-complete → bắn thêm noti "task hoàn thành"
            if (autoCompleted) {
                msg(task.getCreatedBy(), user, task, "TASK_COMPLETED",
                        dn(user) + " hoàn thành \"" + task.getTitle() + "\"");
                telegram.notifyUser(task.getCreatedBy(),
                        "🎉 <b>Task đã hoàn thành</b>\n" +
                        "• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                        "• Người hoàn thành: " + TelegramNotifier.esc(dn(user)));
            }
        }
        return toResponse(task);
    }

    @Transactional
    public TaskResponse completeTask(Long taskId, CompleteTaskRequest req, User user) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Not found"));
        guardCanWork(task, user);
        task.setStatus("COMPLETED"); task.setProgress(100);
        task.setCompletionNote(req.getCompletionNote()); task.setEvidenceImages(req.getEvidenceImages());
        task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
        progressRepo.save(TaskProgressLog.builder().taskId(taskId).userId(user.getId()).username(dn(user))
                .progress(100).note("Hoàn thành").createdAt(System.currentTimeMillis()).build());
        if ("ADMIN".equals(task.getTaskType())) {
            msg(task.getCreatedBy(), user, task, "TASK_COMPLETED", dn(user) + " hoàn thành \"" + task.getTitle() + "\"");

            // Noti Telegram cho người tạo task (admin)
            telegram.notifyUser(task.getCreatedBy(),
                    "🎉 <b>Task đã hoàn thành</b>\n" +
                    "• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                    "• Người hoàn thành: " + TelegramNotifier.esc(dn(user)) +
                    (req.getCompletionNote() != null && !req.getCompletionNote().isBlank()
                            ? "\n• Ghi chú: " + TelegramNotifier.esc(req.getCompletionNote()) : ""));
        }
        return toResponse(task);
    }

    // ─── Extensions ──────────────────────────────────────────────

    @Transactional
    public ExtensionResponse requestExtension(Long taskId, ExtensionRequest req, User user) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new RuntimeException("Not found"));
        guardCanWork(task, user);
        long now = System.currentTimeMillis();
        DeadlineExtension ext = extensionRepo.save(DeadlineExtension.builder()
                .taskId(taskId).requesterId(user.getId()).requesterName(dn(user))
                .newDeadline(req.getNewDeadline()).reason(req.getReason()).createdAt(now).updatedAt(now).build());
        msg(task.getCreatedBy(), user, task, "EXTENSION_REQUEST", dn(user) + " xin gia hạn \"" + task.getTitle() + "\"");

        // Noti Telegram cho người tạo task (admin)
        telegram.notifyUser(task.getCreatedBy(),
                "⏰ <b>Yêu cầu gia hạn task</b>\n" +
                "• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                "• Người xin gia hạn: " + TelegramNotifier.esc(dn(user)) + "\n" +
                "• Hạn mới đề xuất: " + fmtDeadline(req.getNewDeadline()) +
                (req.getReason() != null && !req.getReason().isBlank()
                        ? "\n• Lý do: " + TelegramNotifier.esc(req.getReason()) : ""));
        return extRes(ext);
    }

    @Transactional
    public ExtensionResponse reviewExtension(Long id, ReviewExtensionRequest req, User admin) {
        DeadlineExtension ext = extensionRepo.findById(id).orElseThrow(() -> new RuntimeException("Not found"));
        ext.setStatus(req.getStatus()); ext.setAdminNote(req.getAdminNote());
        ext.setReviewedBy(admin.getId()); ext.setUpdatedAt(System.currentTimeMillis()); extensionRepo.save(ext);
        Task task = taskRepo.findById(ext.getTaskId()).orElse(null);
        if ("APPROVED".equals(req.getStatus()) && task != null) {
            task.setDeadline(ext.getNewDeadline()); task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
            progressRepo.save(TaskProgressLog.builder().taskId(task.getId()).userId(admin.getId()).username(dn(admin))
                    .progress(task.getProgress()).note("Gia hạn deadline").createdAt(System.currentTimeMillis()).build());
        }
        String type = "APPROVED".equals(req.getStatus()) ? "EXTENSION_APPROVED" : "EXTENSION_REJECTED";
        msg(ext.getRequesterId(), admin, task, type,
                ("APPROVED".equals(req.getStatus()) ? "Gia hạn đã được duyệt" : "Gia hạn bị từ chối") +
                        (task != null ? " — \"" + task.getTitle() + "\"" : ""));

        // Noti Telegram cho người xin gia hạn
        boolean approved = "APPROVED".equals(req.getStatus());
        telegram.notifyUser(ext.getRequesterId(),
                (approved ? "✅ <b>Gia hạn được duyệt</b>" : "❌ <b>Gia hạn bị từ chối</b>") +
                (task != null ? "\n• Task: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>" : "") +
                (approved && task != null && task.getDeadline() != null
                        ? "\n• Hạn mới: " + fmtDeadline(task.getDeadline()) : "") +
                (req.getAdminNote() != null && !req.getAdminNote().isBlank()
                        ? "\n• Ghi chú của quản lý: " + TelegramNotifier.esc(req.getAdminNote()) : ""));
        return extRes(ext);
    }

    public Page<DeadlineExtension> getPendingExtensions(int page, int size) {
        return extensionRepo.findByStatusOrderByCreatedAtDesc("PENDING", PageRequest.of(page, size));
    }

    // ─── Query ───────────────────────────────────────────────────

    /** Admin list — chỉ task ADMIN type */
    public TaskPage findAdminTasks(String status, String priority, String category,
                                   Long assigneeId, String q, Long from, Long to, int page, int size) {
        Page<Task> p = assigneeId != null
                ? taskRepo.findByAssigneeAdmin(assigneeId, status, priority, category, q, from, to, PageRequest.of(page, size))
                : taskRepo.findFilteredAdmin(status, priority, category, null, q, from, to, PageRequest.of(page, size));
        return TaskPage.builder().content(p.getContent().stream().map(this::toListItem).toList())
                .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).page(page).size(size).build();
    }

    /** User list — task ADMIN được gán + task PERSONAL do mình tạo */
    public TaskPage findUserTasks(Long userId, String status, String priority, String category,
                                  String q, Long from, Long to, int page, int size) {
        Page<Task> p = taskRepo.findUserTasks(userId, status, priority, category, q, from, to, PageRequest.of(page, size));
        return TaskPage.builder().content(p.getContent().stream().map(this::toListItem).toList())
                .totalElements(p.getTotalElements()).totalPages(p.getTotalPages()).page(page).size(size).build();
    }

    public TaskResponse getTaskDetail(Long id) {
        return toResponse(taskRepo.findById(id).orElseThrow(() -> new RuntimeException("Not found")));
    }

    /** User xem chi tiết — kiểm tra quyền */
    public TaskResponse getTaskDetailForUser(Long id, User user) {
        Task task = taskRepo.findById(id).orElseThrow(() -> new RuntimeException("Not found"));
        if ("PERSONAL".equals(task.getTaskType())) {
            if (!task.getCreatedBy().equals(user.getId())) throw new RuntimeException("Không có quyền xem");
        } else {
            if (!assignmentRepo.existsByTaskIdAndUserId(id, user.getId())) throw new RuntimeException("Không được gán task này");
        }
        return toResponse(task);
    }

    public List<String> getCategories() { return taskRepo.findDistinctCategories(); }

    public List<UserSimple> getAssignableUsers() {
        return userRepo.findAll().stream()
                .filter(u -> u.getRole() != null && !"SUPERADMIN".equals(u.getRole().name()))
                .map(u -> UserSimple.builder().id(u.getId()).username(u.getUsername()).fullName(u.getFullName()).role(u.getRole().name()).build())
                .toList();
    }

    // ─── Dashboard (chỉ task ADMIN) ──────────────────────────────

    public DashboardStats getDashboardStats() {
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // Chỉ lấy task ADMIN cho dashboard
        List<Task> all = taskRepo.findAllAdminTasks();
        // Loại bỏ task đã huỷ cho card thống kê
        List<Task> notCancelled = all.stream().filter(t -> !"CANCELLED".equals(t.getStatus())).toList();
        Set<Long> adminTaskIds = all.stream().map(Task::getId).collect(Collectors.toSet());

        List<TaskAssignment> allAssign = assignmentRepo.findAll().stream()
                .filter(a -> adminTaskIds.contains(a.getTaskId())).toList();
        List<TaskProgressLog> allLogs = progressRepo.findAll().stream()
                .filter(l -> adminTaskIds.contains(l.getTaskId())).toList();
        List<TaskSubItem> allSubs = subItemRepo.findAll().stream()
                .filter(s -> adminTaskIds.contains(s.getTaskId())).toList();

        // ── 5 card mới ──────────────────────────────────────────
        long active = notCancelled.stream().filter(t -> t.getProgress() < 100).count();
        long zeroProgress = notCancelled.stream().filter(t -> t.getProgress() == 0).count();
        long underHundred = notCancelled.stream().filter(t -> t.getProgress() > 0 && t.getProgress() < 100).count();
        long overdue = taskRepo.countOverdueAdmin(now);

        // Gần đến hạn: còn < 10% tổng thời gian (totalDuration = deadline - createdAt)
        List<Task> withDeadline = taskRepo.findActiveAdminWithDeadline();
        long dueSoon = withDeadline.stream().filter(t -> {
            long totalDuration = t.getDeadline() - t.getCreatedAt();
            if (totalDuration <= 0) return false;
            long remaining = t.getDeadline() - now;
            return remaining > 0 && remaining < (long)(totalDuration * 0.1);
        }).count();

        DashboardStats s = DashboardStats.builder()
                .active(active)
                .zeroProgress(zeroProgress)
                .underHundred(underHundred)
                .dueSoon(dueSoon)
                .overdue(overdue)
                .totalTasks(all.size())
                .avgProgress(notCancelled.stream().mapToInt(Task::getProgress).average().orElse(0))
                .build();

        // Assignee stats
        Map<Long, List<TaskAssignment>> byUser = allAssign.stream().collect(Collectors.groupingBy(TaskAssignment::getUserId));
        s.setByAssignee(byUser.entrySet().stream().map(e -> {
            List<Long> ids = e.getValue().stream().map(TaskAssignment::getTaskId).toList();
            List<Task> ut = all.stream().filter(t -> ids.contains(t.getId())).toList();
            String name = e.getValue().get(0).getFullName() != null ? e.getValue().get(0).getFullName() : e.getValue().get(0).getUsername();
            return AssigneeStat.builder().fullName(name).totalTasks(ut.size())
                    .completed(ut.stream().filter(t -> "COMPLETED".equals(t.getStatus())).count())
                    .avgProgress(ut.stream().mapToInt(Task::getProgress).average().orElse(0)).build();
        }).toList());

        // 30-day dailyStats — mỗi ngày đếm task được TẠO vào ngày đó, chia theo progress hiện tại
        List<DailyStat> daily = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            long ds = d.atStartOfDay(zone).toInstant().toEpochMilli();
            long de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            List<Task> createdThisDay = notCancelled.stream()
                    .filter(t -> t.getCreatedAt() >= ds && t.getCreatedAt() < de).toList();
            daily.add(DailyStat.builder().date(d.format(dtf))
                    .notStarted(createdThisDay.stream().filter(t -> t.getProgress() == 0).count())
                    .inProgress(createdThisDay.stream().filter(t -> t.getProgress() > 0 && t.getProgress() < 100).count())
                    .completed(createdThisDay.stream().filter(t -> t.getProgress() >= 100).count())
                    .build());
        }
        s.setDailyStats(daily);

        // Heatmap
        List<HeatmapDay> heatmap = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            long ds = d.atStartOfDay(zone).toInstant().toEpochMilli();
            long de = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            int taskDone = (int) all.stream().filter(t -> "COMPLETED".equals(t.getStatus()) && t.getUpdatedAt() >= ds && t.getUpdatedAt() < de).count();
            int subDone = (int) allSubs.stream().filter(si -> si.getCompleted() && si.getCompletedAt() != null && si.getCompletedAt() >= ds && si.getCompletedAt() < de).count();
            heatmap.add(HeatmapDay.builder().date(d.format(dtf)).count(taskDone + subDone).weekday(d.getDayOfWeek().getValue() - 1).build());
        }
        s.setHeatmap(heatmap);

        // Recent activity
        List<TaskProgressLog> recentLogs = allLogs.stream()
                .sorted(Comparator.comparingLong(TaskProgressLog::getCreatedAt).reversed())
                .limit(20).toList();
        s.setRecentActivity(recentLogs.stream().map(log -> {
            Task t = all.stream().filter(tk -> tk.getId().equals(log.getTaskId())).findFirst().orElse(null);
            String note = log.getNote() != null ? log.getNote() : "";
            String subTitle = null;
            String action = "PROGRESS";
            if (note.startsWith("Hoàn thành: ")) {
                subTitle = note.substring(12).split(" — ")[0];
                action = "SUBTASK_DONE";
            } else if ("Hoàn thành".equals(note)) {
                action = "COMPLETE";
            } else if ("Tạm dừng task".equals(note)) {
                action = "PAUSE";
            } else if ("Mở lại task".equals(note)) {
                action = "RESUME";
            } else if ("Gia hạn deadline".equals(note)) {
                action = "EXTENSION";
            }
            return ActivityEntry.builder()
                    .taskId(log.getTaskId()).taskTitle(t != null ? t.getTitle() : "—")
                    .subItemTitle(subTitle).action(action).username(log.getUsername())
                    .progress(log.getProgress()).timestamp(log.getCreatedAt()).build();
        }).toList());

        return s;
    }

    // ─── Messages ────────────────────────────────────────────────

    public Page<TaskMessage> getMessages(Long userId, int page, int size) {
        return messageRepo.findByRecipientIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }
    public long getUnreadCount(Long userId) { return messageRepo.countByRecipientIdAndIsReadFalse(userId); }

    @Transactional public void markRead(Long messageId, Long userId) {
        messageRepo.findById(messageId).ifPresent(m -> { if (m.getRecipientId().equals(userId)) { m.setIsRead(true); messageRepo.save(m); } });
    }
    @Transactional public void markAllRead(Long userId) {
        messageRepo.findByRecipientIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 500))
                .getContent().stream().filter(m -> !m.getIsRead()).forEach(m -> { m.setIsRead(true); messageRepo.save(m); });
    }

    // ─── Private helpers ─────────────────────────────────────────

    private String dn(User u) { return u.getFullName() != null ? u.getFullName() : u.getUsername(); }

    /**
     * Kiểm tra user có quyền thao tác trên task:
     *  - PERSONAL: phải là người tạo
     *  - ADMIN: phải được gán
     */
    private void guardCanWork(Task task, User u) {
        if ("PERSONAL".equals(task.getTaskType())) {
            if (!task.getCreatedBy().equals(u.getId())) throw new RuntimeException("Không có quyền");
        } else {
            if (!assignmentRepo.existsByTaskIdAndUserId(task.getId(), u.getId())) throw new RuntimeException("Not assigned");
        }
    }

    private void guardNotPaused(Task t) { if ("PAUSED".equals(t.getStatus())) throw new RuntimeException("Task đang tạm dừng"); }

    private void saveSubItems(Long taskId, List<SubItemInput> items) {
        for (int i = 0; i < items.size(); i++) {
            SubItemInput in = items.get(i);
            subItemRepo.save(TaskSubItem.builder().taskId(taskId).title(in.getTitle())
                    .weight(in.getWeight() != null ? in.getWeight() : 0).orderIndex(i)
                    .assigneeId(in.getAssigneeId()).assigneeName(in.getAssigneeName())
                    .build());
        }
    }

    private void recalc(Task task) {
        List<TaskSubItem> subs = subItemRepo.findByTaskIdOrderByOrderIndexAsc(task.getId());
        if (subs.isEmpty()) return;
        int p = subs.stream().filter(TaskSubItem::getCompleted).mapToInt(TaskSubItem::getWeight).sum();
        task.setProgress(Math.min(100, p));
        if (p > 0 && "NOT_STARTED".equals(task.getStatus())) task.setStatus("IN_PROGRESS");
        if (p >= 100) { task.setStatus("COMPLETED"); task.setProgress(100); }
        task.setUpdatedAt(System.currentTimeMillis()); taskRepo.save(task);
    }

    private void assignUsers(Task task, List<Long> ids, User admin) {
        long now = System.currentTimeMillis();
        for (Long uid : ids) {
            User u = userRepo.findById(uid).orElse(null);
            if (u == null || assignmentRepo.existsByTaskIdAndUserId(task.getId(), uid)) continue;
            assignmentRepo.save(TaskAssignment.builder().taskId(task.getId()).userId(uid)
                    .username(u.getUsername()).fullName(u.getFullName()).assignedAt(now).build());
            msg(uid, admin, task, "ASSIGNED", "Bạn được giao task: \"" + task.getTitle() + "\"");

            // Noti Telegram — cá nhân, không gửi vào group
            telegram.notifyUser(uid,
                    "📌 <b>Task mới được giao</b>\n" +
                    "• Tiêu đề: <b>" + TelegramNotifier.esc(task.getTitle()) + "</b>\n" +
                    "• Ưu tiên: " + TelegramNotifier.esc(task.getPriority()) +
                    (task.getDeadline() != null ? "\n• Hạn: " + fmtDeadline(task.getDeadline()) : "") +
                    "\n• Người giao: " + TelegramNotifier.esc(dn(admin)));
        }
    }

    /** Format epoch millis → dd/MM/yyyy HH:mm (GMT+7) cho hiển thị trong noti Telegram */
    private static String fmtDeadline(long epoch) {
        var zdt = java.time.Instant.ofEpochMilli(epoch)
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        return zdt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
    }

    private void notifyAll(Task task, User sender, String type, String content) {
        assignmentRepo.findByTaskId(task.getId()).forEach(a -> msg(a.getUserId(), sender, task, type, content));
    }

    private void msg(Long to, User from, Task task, String type, String content) {
        messageRepo.save(TaskMessage.builder().taskId(task != null ? task.getId() : null)
                .taskTitle(task != null ? task.getTitle() : null).recipientId(to).senderId(from.getId())
                .senderName(dn(from)).type(type).content(content).createdAt(System.currentTimeMillis()).build());
    }

    private TaskResponse toResponse(Task t) {
        var a = assignmentRepo.findByTaskId(t.getId());
        var l = progressRepo.findByTaskIdOrderByCreatedAtDesc(t.getId());
        var su = subItemRepo.findByTaskIdOrderByOrderIndexAsc(t.getId());
        boolean hp = extensionRepo.existsByTaskIdAndStatus(t.getId(), "PENDING");
        return TaskResponse.builder().id(t.getId()).title(t.getTitle()).description(t.getDescription())
                .requirements(t.getRequirements()).status(t.getStatus()).priority(t.getPriority())
                .category(t.getCategory()).deadline(t.getDeadline()).progress(t.getProgress())
                .sequentialSubtasks(t.getSequentialSubtasks()).createdBy(t.getCreatedBy()).createdByName(t.getCreatedByName())
                .evidenceImages(t.getEvidenceImages()).completionNote(t.getCompletionNote())
                .createdAt(t.getCreatedAt()).updatedAt(t.getUpdatedAt()).taskType(t.getTaskType()).hasPendingExtension(hp)
                .assignees(a.stream().map(x -> AssigneeInfo.builder().userId(x.getUserId()).username(x.getUsername()).fullName(x.getFullName()).build()).toList())
                .subItems(su.stream().map(x -> SubItemResponse.builder().id(x.getId()).title(x.getTitle()).weight(x.getWeight()).orderIndex(x.getOrderIndex())
                        .assigneeId(x.getAssigneeId()).assigneeName(x.getAssigneeName())
                        .completed(x.getCompleted()).completedByName(x.getCompletedByName()).completedAt(x.getCompletedAt()).completionNote(x.getCompletionNote()).build()).toList())
                .progressLogs(l.stream().map(x -> ProgressLogResponse.builder().id(x.getId()).progress(x.getProgress()).note(x.getNote())
                        .attachments(x.getAttachments()).username(x.getUsername()).createdAt(x.getCreatedAt()).build()).toList())
                .build();
    }

    private TaskListItem toListItem(Task t) {
        var a = assignmentRepo.findByTaskId(t.getId());
        var su = subItemRepo.findByTaskIdOrderByOrderIndexAsc(t.getId());
        boolean hp = extensionRepo.existsByTaskIdAndStatus(t.getId(), "PENDING");
        return TaskListItem.builder().id(t.getId()).title(t.getTitle()).status(t.getStatus()).priority(t.getPriority())
                .category(t.getCategory()).deadline(t.getDeadline()).progress(t.getProgress())
                .sequentialSubtasks(t.getSequentialSubtasks()).createdByName(t.getCreatedByName()).createdAt(t.getCreatedAt())
                .taskType(t.getTaskType()).hasPendingExtension(hp)
                .assignees(a.stream().map(x -> AssigneeInfo.builder().userId(x.getUserId()).username(x.getUsername()).fullName(x.getFullName()).build()).toList())
                .subItems(su.stream().map(x -> SubItemResponse.builder().id(x.getId()).title(x.getTitle()).weight(x.getWeight()).orderIndex(x.getOrderIndex())
                        .assigneeId(x.getAssigneeId()).assigneeName(x.getAssigneeName())
                        .completed(x.getCompleted()).completedByName(x.getCompletedByName()).completedAt(x.getCompletedAt()).build()).toList())
                .build();
    }

    private ExtensionResponse extRes(DeadlineExtension e) {
        return ExtensionResponse.builder().id(e.getId()).taskId(e.getTaskId()).requesterName(e.getRequesterName())
                .newDeadline(e.getNewDeadline()).reason(e.getReason()).status(e.getStatus()).adminNote(e.getAdminNote()).createdAt(e.getCreatedAt()).build();
    }
}