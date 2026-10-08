package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.PassengerDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PassengerDocumentRepository extends JpaRepository<PassengerDocument, Long> {

    List<PassengerDocument> findByPassengerIdOrderByIdAsc(Long passengerId);

    Optional<PassengerDocument> findByPassengerIdAndType(Long passengerId, String type);
}