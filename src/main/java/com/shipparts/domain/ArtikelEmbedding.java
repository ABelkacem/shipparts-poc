package com.shipparts.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "artikel_embedding")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtikelEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artikel_id", nullable = false)
    private Artikel artikel;

    /**
     * Stored as PostgreSQL VECTOR(384).
     * We use float[] mapped via @Column(columnDefinition="vector(384)").
     * The pgvector JDBC driver handles the cast.
     */
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private float[] embedding;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    private Integer version = 1;

    @Column(name = "created_at")
    private Instant createdAt;
}
