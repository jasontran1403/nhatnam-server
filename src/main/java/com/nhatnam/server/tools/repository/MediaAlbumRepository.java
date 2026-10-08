package com.nhatnam.server.tools.repository;

import com.nhatnam.server.tools.entity.MediaAlbum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MediaAlbumRepository extends JpaRepository<MediaAlbum, Long> {
    List<MediaAlbum> findAllByOrderByCreatedAtDesc();
}
