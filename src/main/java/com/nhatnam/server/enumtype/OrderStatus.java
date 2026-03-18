package com.nhatnam.server.enumtype;

public enum OrderStatus {
    PENDING,      // Đang chờ xác nhận
    CONFIRMED,    // Đã xác nhận
    PREPARING,    // Đang chuẩn bị
    READY,        // Sẵn sàng
    DELIVERING,   // Đang giao
    COMPLETED,    // Hoàn thành
    CANCELLED,     // Đã hủy
    FAILED
}