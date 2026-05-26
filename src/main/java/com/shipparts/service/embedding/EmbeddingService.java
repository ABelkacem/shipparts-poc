package com.shipparts.service.embedding;

import com.shipparts.domain.Artikel;
import com.shipparts.domain.ArtikelEmbedding;
import com.shipparts.repository.ArtikelEmbeddingRepository;
import com.shipparts.repository.ArtikelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * KI #2 — Embedding Service.
 *
 * Converts article text and query text into vectors using Spring AI EmbeddingModel.
 * Works with both Ollama (nomic-embed-text) and OpenAI (text-embedding-3-small).
 *
 * Builds a rich input text per article:
 *   beschreibung_lang + hersteller + maschinentyp + tags + synonyme
 * This maximises semantic match surface for queries.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final ArtikelEmbeddingRepository embeddingRepository;
    private final ArtikelRepository artikelRepository;

    @Value("${shipparts.embedding.model-name:nomic-embed-text}")
    private String modelName;

    // ── Query Embedding ───────────────────────────────────────────────────

    /**
     * Embed a query string for similarity search.
     * This is called per incoming request (low latency required).
     */
    public float[] embedQuery(String queryText) {
        log.debug("Embedding query: {}", queryText.substring(0, Math.min(80, queryText.length())));
        List<Double> vector = embeddingModel.embed(queryText);
        return toFloatArray(vector);
    }

    // ── Article Indexing ──────────────────────────────────────────────────

    /**
     * Embed a single article and persist the vector.
     * Called during ERP sync and nightly re-embedding.
     */
    @Transactional
    public ArtikelEmbedding embedAndSaveArtikel(Artikel artikel) {
        String inputText = buildArtikelText(artikel);
        float[] vector   = toFloatArray(embeddingModel.embed(inputText));

        // Upsert: update existing embedding or create new one
        ArtikelEmbedding embedding = embeddingRepository
                .findByArtikelId(artikel.getId())
                .orElse(ArtikelEmbedding.builder()
                        .artikel(artikel)
                        .build());

        embedding.setEmbedding(vector);
        embedding.setModelName(modelName);
        embedding.setCreatedAt(Instant.now());

        ArtikelEmbedding saved = embeddingRepository.save(embedding);
        log.debug("Embedded artikel {}: {} dims", artikel.getArtikelNr(), vector.length);
        return saved;
    }

    /**
     * Embed all articles in the database (full index rebuild).
     * Used on first startup or after a major data change.
     */
    @Transactional
    public int embedAllArtikels() {
        List<Artikel> all = artikelRepository.findAll();
        log.info("Starting full embedding of {} articles", all.size());
        int count = 0;
        for (Artikel artikel : all) {
            try {
                embedAndSaveArtikel(artikel);
                count++;
            } catch (Exception e) {
                log.error("Failed to embed artikel {}: {}", artikel.getArtikelNr(), e.getMessage());
            }
        }
        log.info("Embedding complete: {}/{} articles indexed", count, all.size());
        return count;
    }

    /**
     * Embed a list of articles (incremental update from ERP sync or feedback loop).
     */
    @Transactional
    public int embedArtikels(List<Artikel> artikels) {
        int count = 0;
        for (Artikel a : artikels) {
            try {
                embedAndSaveArtikel(a);
                count++;
            } catch (Exception e) {
                log.warn("Skipping embedding for {}: {}", a.getArtikelNr(), e.getMessage());
            }
        }
        return count;
    }

    // ── Vector Formatting for pgvector ───────────────────────────────────

    /**
     * Format float[] as PostgreSQL vector literal: '[0.1,0.2,...]'
     * Required for the native pgvector similarity query.
     */
    public String formatAsVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ── Private ───────────────────────────────────────────────────────────

    /**
     * Build a rich text representation of an article for embedding.
     * Includes synonyme[] from the feedback loop for improved semantic coverage.
     */
    private String buildArtikelText(Artikel artikel) {
        StringBuilder sb = new StringBuilder();
        sb.append(artikel.getBeschreibungLang()).append(" ");
        if (artikel.getBeschreibungKurz() != null) sb.append(artikel.getBeschreibungKurz()).append(" ");
        if (artikel.getHersteller()       != null) sb.append(artikel.getHersteller()).append(" ");
        if (artikel.getMaschinentyp()     != null) sb.append(artikel.getMaschinentyp()).append(" ");
        if (artikel.getTags()             != null) sb.append(String.join(" ", artikel.getTags())).append(" ");
        if (artikel.getSynonyme()         != null) sb.append(String.join(" ", artikel.getSynonyme()));
        return sb.toString().trim();
    }

    private float[] toFloatArray(List<Double> doubles) {
        float[] result = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            result[i] = doubles.get(i).floatValue();
        }
        return result;
    }
}
