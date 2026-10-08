package com.nhatnam.server.tools.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Bảng nối album ↔ asset. Một ảnh có thể nằm trong nhiều album.
 */
@Entity
@Table(name = "media_album_asset",
       uniqueConstraints = @UniqueConstraint(columnNames = {"album_id", "asset_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MediaAlbumAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "album_id", nullable = false)
    private Long albumId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "added_at", nullable = false)
    private Long addedAt;
}
