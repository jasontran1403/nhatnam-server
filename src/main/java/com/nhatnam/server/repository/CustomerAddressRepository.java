package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    // Lấy tất cả địa chỉ của khách hàng
    List<CustomerAddress> findByCustomerId(Long customerId);

    // Tìm địa chỉ mặc định của khách hàng
    @Query("SELECT ca FROM CustomerAddress ca WHERE ca.customer.id = :customerId AND ca.isDefault = true")
    CustomerAddress findDefaultAddressByCustomerId(@Param("customerId") Long customerId);

    // Đặt tất cả địa chỉ của khách hàng thành không mặc định
    @Modifying
    @Transactional
    @Query("UPDATE CustomerAddress ca SET ca.isDefault = false WHERE ca.customer.id = :customerId")
    void resetDefaultAddress(@Param("customerId") Long customerId);

    // Xóa tất cả địa chỉ của khách hàng
    @Modifying
    @Transactional
    @Query("DELETE FROM CustomerAddress ca WHERE ca.customer.id = :customerId")
    void deleteByCustomerId(@Param("customerId") Long customerId);
}