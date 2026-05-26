package com.shipparts.service.matching;

import com.shipparts.domain.Artikel;
import com.shipparts.dto.ExtractionResult;
import com.shipparts.dto.MatchResult;
import com.shipparts.repository.ArtikelEmbeddingRepository;
import com.shipparts.repository.ArtikelEmbeddingRepository.SimilarityResult;
import com.shipparts.repository.ArtikelRepository;
import com.shipparts.service.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Matching Service — routes between two strategies:
 *
 * Path A: Artikel-Nr. present → SQL direct lookup (konfidenz = 1.0)
 * Path B: No Artikel-Nr. → Semantic similarity search via pgvector
 * Path C: Fallback → Text LIKE keyword search
 *
 * Returns top-K candidates ordered by confidence for offer generation
 * and human review.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {

    private final ArtikelRepository artikelRepository;
    private final ArtikelEmbeddingRepository embeddingRepository;
    private final EmbeddingService embeddingService;

    @Value("${shipparts.matching.top-k:3}")
    private int topK;

    @Value("${shipparts.matching.confidence-threshold:0.85}")
    private double confidenceThreshold;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Find matching articles for the extracted request.
     * Returns up to topK results, ordered by confidence descending.
     */
    public List<MatchResult> findMatches(ExtractionResult extraction) {
        log.debug("Finding matches: artikelNr={}, beschreibung={}",
                extraction.getArtikelNummer(), extraction.getArtikelBeschreibung());

        // Path A: Direct SQL lookup if artikel_nr present
        if (extraction.hasArtikelNummer()) {
            Optional<MatchResult> direct = directMatch(extraction.getArtikelNummer());
            if (direct.isPresent()) {
                log.info("Direct match found for artikel_nr={}", extraction.getArtikelNummer());
                return List.of(direct.get());
            }
            log.warn("Artikel-Nr. {} not found in DB, falling through to semantic search",
                    extraction.getArtikelNummer());
        }

        // Path B: Semantic similarity search
        String queryText = buildQueryText(extraction);
        if (!queryText.isBlank()) {
            List<MatchResult> semantic = semanticSearch(queryText);
            if (!semantic.isEmpty()) {
                log.info("Semantic search found {} candidates (top score: {})",
                        semantic.size(), semantic.get(0).getKonfidenz());
                return semantic;
            }
        }

        // Path C: Keyword fallback
        log.info("Semantic search returned no results, using keyword fallback");
        return keywordFallback(extraction.getArtikelBeschreibung());
    }

    /**
     * Returns true if the best match exceeds the confidence threshold.
     * If true, the offer can be sent automatically (Dunkel-Prozess).
     */
    public boolean isHighConfidence(List<MatchResult> matches) {
        return !matches.isEmpty() && matches.get(0).getKonfidenz() >= confidenceThreshold;
    }

    // ── Path A: Direct SQL Match ──────────────────────────────────────────

    private Optional<MatchResult> directMatch(String artikelNr) {
        return artikelRepository.findByArtikelNr(artikelNr.trim())
                .filter(a -> a.getBestand() > 0)
                .map(a -> MatchResult.fromArtikel(a, 1.0, MatchResult.MatchType.DIRECT_ARTIKELNR));
    }

    // ── Path B: Semantic Similarity Search ───────────────────────────────

    private List<MatchResult> semanticSearch(String queryText) {
        try {
            float[] queryVector = embeddingService.embedQuery(queryText);
            String vectorLiteral = embeddingService.formatAsVectorLiteral(queryVector);

            List<SimilarityResult> results =
                    embeddingRepository.findTopKSimilar(vectorLiteral, topK);

            List<MatchResult> matches = new ArrayList<>();
            for (SimilarityResult r : results) {
                artikelRepository.findById(r.getArtikel_id()).ifPresent(artikel -> {
                    double score = r.getSimilarity() != null ? r.getSimilarity() : 0.0;
                    matches.add(MatchResult.fromArtikel(
                            artikel, score, MatchResult.MatchType.SEMANTIC_EMBEDDING));
                });
            }
            return matches;

        } catch (Exception e) {
            log.error("Semantic search failed", e);
            return List.of();
        }
    }

    // ── Path C: Keyword Fallback ──────────────────────────────────────────

    private List<MatchResult> keywordFallback(String description) {
        if (description == null || description.isBlank()) return List.of();
        // Use first meaningful word as keyword
        String keyword = description.split("\\s+")[0];
        return artikelRepository.searchByText(keyword).stream()
                .limit(topK)
                .map(a -> MatchResult.fromArtikel(a, 0.5, MatchResult.MatchType.KEYWORD_FALLBACK))
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Build a rich query string from all extracted fields.
     * More context = better embedding = better match.
     */
    private String buildQueryText(ExtractionResult e) {
        StringBuilder sb = new StringBuilder();
        if (e.getArtikelBeschreibung() != null) sb.append(e.getArtikelBeschreibung()).append(" ");
        if (e.getHersteller()          != null) sb.append(e.getHersteller()).append(" ");
        if (e.getMaschinentyp()        != null) sb.append(e.getMaschinentyp()).append(" ");
        return sb.toString().trim();
    }
}
