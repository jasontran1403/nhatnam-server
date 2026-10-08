package com.nhatnam.server.tools.repository;

import com.nhatnam.server.tools.entity.MediaAlbumAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MediaAlbumAssetRepository extends JpaRepository<MediaAlbumAsset, Long> {

    List<MediaAlbumAsset> findAllByAlbumId(Long albumId);

    long countByAlbumId(Long albumId);

    void deleteAllByAlbumId(Long albumId);

    void deleteAllByAlbumIdAndAssetIdIn(Long albumId, List<Long> assetIds);

    boolean existsByAlbumIdAndAssetId(Long albumId, Long assetId);

    /** Lấy danh sách asset ID thuộc album */
    @Query("SELECT a.assetId FROM MediaAlbumAsset a WHERE a.albumId = :albumId")
    List<Long> findAssetIdsByAlbumId(@Param("albumId") Long albumId);
}
