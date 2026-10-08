package com.nhatnam.server.entity;

/**
 * Trạng thái bot MT5 do UI/BE điều khiển, MT5 fetch mỗi 1s.
 *
 * RUNNING  — chạy bình thường (mở/đóng lệnh theo logic nội tại).
 * PAUSED   — không mở lệnh mới, KHÔNG đóng lệnh cũ.
 * STOPPING — không mở lệnh mới, đóng HẾT lệnh đang mở; sau khi đóng xong
 *            vẫn giữ STOPPING cho đến khi user thao tác lại.
 */
public enum Mt5BotState {
    RUNNING,
    PAUSED,
    STOPPING
}
