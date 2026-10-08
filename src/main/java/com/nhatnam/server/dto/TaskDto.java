package com.nhatnam.server.dto;

import lombok.*;
import java.util.List;

public class TaskDto {

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SubItemInput {
        private String title;
        private Integer weight;
        /** Gán đầu mục cho 1 người cụ thể (userId). null = ai cũng xử lý được */
        private Long assigneeId;
        private String assigneeName;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SubItemResponse {
        private Long id; private String title; private Integer weight; private Integer orderIndex;
        private Long assigneeId; private String assigneeName;
        private Boolean completed; private String completedByName; private Long completedAt; private String completionNote;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CompleteSubItemRequest { private String note; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateTaskRequest {
        private String title; private String description; private String requirements;
        private String priority; private String category; private Long deadline;
        private List<Long> assigneeIds; private Boolean sequentialSubtasks; private List<SubItemInput> subItems;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateTaskRequest {
        private String title; private String description; private String requirements;
        private String priority; private String category; private Long deadline; private String status;
        private List<Long> assigneeIds; private Boolean sequentialSubtasks; private List<SubItemInput> subItems;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateProgressRequest { private Integer progress; private String note; private String attachments; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CompleteTaskRequest { private String completionNote; private String evidenceImages; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ExtensionRequest { private Long newDeadline; private String reason; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReviewExtensionRequest { private String status; private String adminNote; }

    /** Request để admin gán thêm/đổi người xử lý */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReassignRequest {
        private List<Long> assigneeIds;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TaskResponse {
        private Long id; private String title; private String description; private String requirements;
        private String status; private String priority; private String category; private Long deadline;
        private Integer progress; private Boolean sequentialSubtasks; private Long createdBy; private String createdByName;
        private String evidenceImages; private String completionNote; private Long createdAt; private Long updatedAt;
        private String taskType;
        private List<AssigneeInfo> assignees; private List<SubItemResponse> subItems;
        private List<ProgressLogResponse> progressLogs; private Boolean hasPendingExtension;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TaskListItem {
        private Long id; private String title; private String status; private String priority; private String category;
        private Long deadline; private Integer progress; private Boolean sequentialSubtasks; private String createdByName;
        private Long createdAt; private String taskType;
        private List<AssigneeInfo> assignees; private List<SubItemResponse> subItems;
        private Boolean hasPendingExtension;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AssigneeInfo { private Long userId; private String username; private String fullName; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ProgressLogResponse {
        private Long id; private Integer progress; private String note; private String attachments;
        private String username; private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MessageResponse {
        private Long id; private Long taskId; private String taskTitle; private String type;
        private String content; private String senderName; private Boolean isRead; private Long createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ExtensionResponse {
        private Long id; private Long taskId; private String requesterName; private Long newDeadline;
        private String reason; private String status; private String adminNote; private Long createdAt;
    }

    // ─── Dashboard ───────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DashboardStats {
        /** 5 card trên cùng */
        private long active;        // task đang hoạt động: progress >= 0 AND progress < 100, chưa huỷ
        private long zeroProgress;  // task chưa bắt đầu: progress == 0
        private long underHundred;  // task đang xử lý: 0 < progress < 100
        private long dueSoon;       // task gần đến hạn: còn < 10% thời gian
        private long overdue;       // task trễ hạn: deadline < now AND chưa hoàn thành

        /** Giữ lại cho các phần khác nếu cần */
        private long totalTasks; private double avgProgress;
        private List<AssigneeStat> byAssignee;
        private List<DailyStat> dailyStats;
        private List<HeatmapDay> heatmap;
        private List<ActivityEntry> recentActivity;
    }

    /** Thống kê theo ngày — dùng cho bar chart 30 ngày */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DailyStat {
        private String date;
        private long notStarted;   // progress = 0
        private long inProgress;   // 0 < progress < 100
        private long completed;    // progress = 100
    }

    /** Lượt hoàn thành task/subtask — dùng cho heatmap */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HeatmapDay {
        private String date; private int count; private int weekday;
    }

    /** Dòng log hoạt động gần nhất */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ActivityEntry {
        private Long taskId; private String taskTitle; private String subItemTitle;
        private String action; // SUBTASK_DONE, PAUSE, RESUME, EXTENSION, PROGRESS, COMPLETE
        private String username; private Integer progress; private Long timestamp;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AssigneeStat {
        private String fullName; private long totalTasks; private long completed; private double avgProgress;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CategoryStat { private String category; private long count; private long completed; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PriorityStat { private String priority; private long count; private long completed; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UserSimple { private Long id; private String username; private String fullName; private String role; }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TaskPage {
        private List<TaskListItem> content; private long totalElements; private int totalPages; private int page; private int size;
    }
}