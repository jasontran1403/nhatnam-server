package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sequence table để sinh số thứ tự import batch theo ngày.
 * Key: "IS-20260307" → lastSeq: 3 (tức đã có 3 batch ngày đó)
 */
@Entity
@Table(name = "import_batch_sequence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatchSequence {

    @Id
    @Column(name = "date_key", length = 12)   // "IS-20260307"
    private String dateKey;

    @Column(name = "last_seq", nullable = false)
    private Long lastSeq;
}