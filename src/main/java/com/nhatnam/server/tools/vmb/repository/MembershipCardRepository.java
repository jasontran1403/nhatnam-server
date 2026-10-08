package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.MembershipCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MembershipCardRepository extends JpaRepository<MembershipCard, Long> {

    List<MembershipCard> findByPassengerIdOrderByIdAsc(Long passengerId);
}
