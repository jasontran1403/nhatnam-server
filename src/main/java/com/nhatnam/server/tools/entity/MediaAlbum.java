package com.nhatnam.server.tools.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Album nhóm ảnh/video. Một file có thể nằm trong nhiều album.
 * Yêu thích (favorite) là trường riêng trên MediaAsset, không liên quan album.
 */
@Entity
@Table(name = "media_album")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MediaAlbum {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
