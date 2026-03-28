// repository/pos/PosCustomerRepository.java
package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosCustomerRepository extends JpaRepository<PosCustomer, Long> {

    Optional<PosCustomer> findByPhone(String phone);

    List<PosCustomer> findByPhoneContaining(String phone);

    List<PosCustomer> findByStoreId(long storeId);
}