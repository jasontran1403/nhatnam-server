package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // Tìm theo số điện thoại
    Optional<Customer> findByPhone(String phone);

    // Tìm khách hàng đang active
    Optional<Customer> findByIdAndIsActiveTrue(Long id);

    // Tìm theo tên (tìm kiếm gần đúng)
    List<Customer> findByNameContainingIgnoreCase(String name);

    // Tìm theo số điện thoại (tìm kiếm gần đúng)
    List<Customer> findByPhoneContaining(String phone);

    // Kiểm tra số điện thoại đã tồn tại chưa
    boolean existsByPhone(String phone);

    // Lấy tất cả khách hàng đang active
    List<Customer> findByIsActiveTrue();

    // Tìm kiếm khách hàng theo tên hoặc số điện thoại (phân trang)
    @Query("SELECT c FROM Customer c WHERE c.isActive = true AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "c.phone LIKE CONCAT('%', :keyword, '%'))")
    Page<Customer> searchCustomers(@Param("keyword") String keyword, Pageable pageable);

    // Đếm số điện thoại (dùng để tạo mã)
    long count();
}