package com.nhatnam.server.tools.repository;

import com.nhatnam.server.tools.entity.ToolsUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ToolsUserRepository extends JpaRepository<ToolsUser, Long> {

    /** Đăng nhập: tìm theo username đã lower-case */
    Optional<ToolsUser> findByUsername(String username);

    /** Có tồn tại (không phân biệt hoa/thường) — dùng khi tạo user mới để chống trùng */
    boolean existsByUsernameIgnoreCase(String username);

    /** Danh sách cho trang quản lý — sắp admin lên trước, sau đó theo tên */
    List<ToolsUser> findAllByOrderByIsAdminDescUsernameAsc();
}
