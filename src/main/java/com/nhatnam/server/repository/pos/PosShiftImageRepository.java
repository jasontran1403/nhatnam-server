// src/main/java/com/nhatnam/server/repository/pos/PosShiftImageRepository.java
package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosShiftImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PosShiftImageRepository extends JpaRepository<PosShiftImage, Long> {
}