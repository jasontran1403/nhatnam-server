package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.entity.TodoTask;
import com.nhatnam.server.tools.vmb.repository.TodoTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * Nghiệp vụ Todo Task.
 *
 * ── Status transitions ─────────────────────────────────
 * PENDING → COMPLETED  (kèm ảnh minh chứng bắt buộc)
 * PENDING → CANCELLED  (kèm note)
 * PENDING → PENDING    (extend: đổi deadline + note tùy chọn)
 *
 * COMPLETED / CANCELLED là trạng thái cuối — không cho đổi tiếp.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class TodoTaskService {

    private static final Set<String> ALL_STATUS = Set.of("PENDING", "COMPLETED", "CANCELLED");

    private final TodoTaskRepository repo;
    private final VmbStorageService storage;

    // ── List ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TodoTask> listByDeadlineRange(Long from, Long to) {
        return repo.findByDeadlineRange(from, to);
    }

    @Transactional(readOnly = true)
    public List<TodoTask> listPendingInRange(Long from, Long to) {
        return repo.findPendingInRange(from, to);
    }

    // ── Create / Delete ─────────────────────────────

    @Transactional
    public TodoTask create(String unitName, String description, Long deadline, String note) {
        if (unitName == null || unitName.isBlank()) throw new ToolsException("Tên đơn vị không được trống.");
        if (deadline == null || deadline <= 0)      throw new ToolsException("Deadline không hợp lệ.");
        long now = System.currentTimeMillis();
        TodoTask t = TodoTask.builder()
                .unitName(unitName.trim())
                .description(description == null ? "" : description.trim())
                .deadline(deadline)
                .status("PENDING")
                .note(note == null ? "" : note.trim())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return repo.save(t);
    }

    @Transactional
    public TodoTask update(Long id, String unitName, String description, Long deadline, String note) {
        TodoTask t = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy task."));
        if (!"PENDING".equals(t.getStatus())) throw new ToolsException("Chỉ được sửa task đang thực hiện.");
        if (unitName != null && !unitName.isBlank()) t.setUnitName(unitName.trim());
        if (description != null) t.setDescription(description.trim());
        if (deadline != null && deadline > 0) t.setDeadline(deadline);
        if (note != null) t.setNote(note.trim());
        t.setUpdatedAt(System.currentTimeMillis());
        return t;
    }

    @Transactional
    public void delete(Long id) {
        TodoTask t = repo.findById(id).orElse(null);
        if (t == null) return;
        if (t.getConfirmationFile() != null) storage.delete(t.getConfirmationFile());
        repo.delete(t);
    }

    // ── Transitions ─────────────────────────────────

    @Transactional
    public TodoTask complete(Long id, MultipartFile file, String note) {
        TodoTask t = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy task."));
        if (!"PENDING".equals(t.getStatus())) throw new ToolsException("Chỉ hoàn thành được task đang thực hiện.");
        if (file == null || file.isEmpty())   throw new ToolsException("Cần đính kèm ảnh/PDF minh chứng.");
        // Xóa file cũ nếu có
        if (t.getConfirmationFile() != null) storage.delete(t.getConfirmationFile());
        var stored = storage.store(file);
        t.setConfirmationFile(stored.storedName());
        t.setConfirmationOriginal(stored.originalName());
        t.setStatus("COMPLETED");
        if (note != null) t.setNote(note.trim());
        t.setUpdatedAt(System.currentTimeMillis());
        return t;
    }

    @Transactional
    public TodoTask extend(Long id, Long newDeadline, String note) {
        TodoTask t = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy task."));
        if (!"PENDING".equals(t.getStatus())) throw new ToolsException("Chỉ gia hạn được task đang thực hiện.");
        if (newDeadline == null || newDeadline <= 0) throw new ToolsException("Thời hạn mới không hợp lệ.");
        t.setDeadline(newDeadline);
        if (note != null) t.setNote(note.trim());
        t.setUpdatedAt(System.currentTimeMillis());
        return t;
    }

    @Transactional
    public TodoTask cancel(Long id, String note) {
        TodoTask t = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy task."));
        if (!"PENDING".equals(t.getStatus())) throw new ToolsException("Chỉ hủy được task đang thực hiện.");
        if (note == null || note.isBlank())   throw new ToolsException("Cần nhập lý do hủy.");
        t.setStatus("CANCELLED");
        t.setNote(note.trim());
        t.setUpdatedAt(System.currentTimeMillis());
        return t;
    }
}