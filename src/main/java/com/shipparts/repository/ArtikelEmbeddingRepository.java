package com.shipparts.repository;

import com.shipparts.domain.ArtikelEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ArtikelEmbeddingRepository extends JpaRepository<ArtikelEmbedding, UUID> {

    Optional<ArtikelEmbedding> findByArtikelId(UUID artikelId);

    /**
     * Semantic similarity search using pgvector cosine distance.
     *
     * The query embedding is cast to VECTOR type in PostgreSQL.
     * Returns top-k articles ordered by cosine similarity (highest first).
     * The confidence score is: 1 - cosine_distance
     *
     * Note: :embedding is passed as a float[] and cast via ::vector
     */
    @Query(value = """
        SELECT
            ae.id,
            ae.artikel_id,
            ae.embedding,
            ae.model_name,
            ae.version,
            ae.created_at,
            (1 - (ae.embedding <=> CAST(:embedding AS vector))) AS similarity
        FROM artikel_embedding ae
        JOIN artikel a ON a.id = ae.artikel_id
        WHERE a.bestand > 0
        ORDER BY ae.embedding <=> CAST(:embedding AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<SimilarityResult> findTopKSimilar(
            @Param("embedding") String embedding,
            @Param("topK") int topK
    );

    /**
     * Projection interface for similarity results.
     * Spring Data will map the native query columns.
     */
    interface SimilarityResult {
        UUID getArtikel_id();
        Double getSimilarity();
    }
}
