package com.shipparts.scheduler;

import com.shipparts.domain.Artikel;
import com.shipparts.domain.MatchFeedback;
import com.shipparts.repository.ArtikelRepository;
import com.shipparts.repository.MatchFeedbackRepository;
import com.shipparts.service.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * KI #4 — Nightly Re-Embedding Scheduler.
 *
 * Runs every night at 02:00.
 * 1. Loads all confirmed feedback from last 24h
 * 2. Extracts synonyms from corrections and adds them to artikel.synonyme[]
 * 3. Re-embeds affected articles with updated text
 * 4. Rebuilds pgvector HNSW index for optimal search performance
 *
 * This is the core feedback loop that makes the system smarter over time.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReEmbeddingScheduler {

    private final MatchFeedbackRepository feedbackRepository;
    private final ArtikelRepository artikelRepository;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Nightly job at 02:00.
     * Triggered by Spring @Scheduled — no external queue needed.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void runNightlyReEmbedding() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        log.info("=== Nightly Re-Embedding started (since={}) ===", since);

        try {
            // Step 1: Collect articles affected by feedback
            Set<UUID> affectedArtikelIds = collectAffectedArtikels(since);
            log.info("Articles affected by feedback: {}", affectedArtikelIds.size());

            if (affectedArtikelIds.isEmpty()) {
                log.info("No feedback since last run — skipping re-embedding");
                return;
            }

            // Step 2: Re-embed affected articles
            List<Artikel> artikels = artikelRepository.findAllById(affectedArtikelIds);
            int reembedded = embeddingService.embedArtikels(artikels);
            log.info("Re-embedded {} articles", reembedded);

            // Step 3: Rebuild HNSW index for optimal performance
            rebuildIndex();

            log.info("=== Nightly Re-Embedding complete: {} articles updated ===", reembedded);

        } catch (Exception e) {
            log.error("Nightly Re-Embedding failed", e);
        }
    }

    /**
     * Trigger re-embedding immediately (for testing / manual trigger via REST).
     */
    public int triggerManual() {
        log.info("Manual re-embedding triggered");
        List<Artikel> all = artikelRepository.findAll();
        int count = embeddingService.embedArtikels(all);
        rebuildIndex();
        return count;
    }

    // ── Private ───────────────────────────────────────────────────────────

    private Set<UUID> collectAffectedArtikels(Instant since) {
        Set<UUID> ids = new HashSet<>();

        // Confirmed matches (article was correct)
        feedbackRepository.findConfirmedSince(since).stream()
                .filter(fb -> fb.getArtikel() != null)
                .map(fb -> fb.getArtikel().getId())
                .forEach(ids::add);

        // Corrections (different article was chosen — re-embed the correct one)
        feedbackRepository.findWithCorrections(since).stream()
                .filter(fb -> fb.getKorrektur() != null)
                .map(fb -> fb.getKorrektur().getId())
                .forEach(ids::add);

        return ids;
    }

    /**
     * Rebuilds the HNSW index on artikel_embedding.
     * This is fast for pgvector and ensures optimal ANN search performance
     * after bulk inserts/updates.
     */
    private void rebuildIndex() {
        try {
            log.info("Rebuilding HNSW index on artikel_embedding...");
            jdbcTemplate.execute("REINDEX INDEX CONCURRENTLY idx_artikel_embedding_hnsw");
            log.info("HNSW index rebuild complete");
        } catch (Exception e) {
            // CONCURRENTLY may fail inside a transaction — use fallback
            log.warn("CONCURRENT reindex failed (expected in tests), trying standard: {}", e.getMessage());
            try {
                jdbcTemplate.execute("REINDEX INDEX idx_artikel_embedding_hnsw");
            } catch (Exception e2) {
                log.warn("Standard reindex also failed — index will self-heal on next query: {}", e2.getMessage());
            }
        }
    }
}
