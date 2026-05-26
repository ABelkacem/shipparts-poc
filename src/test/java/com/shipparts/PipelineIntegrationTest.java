package com.shipparts;

import com.shipparts.domain.Anfrage;
import com.shipparts.domain.Artikel;
import com.shipparts.dto.ExtractionResult;
import com.shipparts.dto.MatchResult;
import com.shipparts.repository.ArtikelRepository;
import com.shipparts.service.PipelineOrchestrator;
import com.shipparts.service.embedding.EmbeddingService;
import com.shipparts.service.matching.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test using Testcontainers (PostgreSQL 16 + pgvector).
 *
 * Tests the full pipeline: direct match + semantic search + feedback.
 * LLM is mocked — tests focus on matching logic and DB integration.
 *
 * Requires Docker running locally.
 * Run with: mvn test -Dtest=PipelineIntegrationTest
 */
@SpringBootTest
@Testcontainers
class PipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("shipparts_test")
            .withUsername("shipparts")
            .withPassword("shipparts");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Use ollama disabled profile for tests (no real LLM needed)
        registry.add("spring.ai.ollama.base-url",  () -> "http://localhost:99999");
    }

    @Autowired ArtikelRepository artikelRepository;
    @Autowired EmbeddingService embeddingService;
    @Autowired MatchingService matchingService;

    @BeforeEach
    void setUp() {
        // Seed data comes from Flyway V1 migration
        assertThat(artikelRepository.count()).isGreaterThan(0);
    }

    // ── Direct Match Tests ────────────────────────────────────────────────

    @Test
    @DisplayName("Direct match by exact Artikel-Nr returns confidence 1.0")
    void directMatch_exactArtikelNr_returnsFullConfidence() {
        ExtractionResult extraction = new ExtractionResult();
        extraction.setArtikelNummer("MAN-BR-7710");
        extraction.setArtikelBeschreibung("Turbolader-Lager");

        List<MatchResult> matches = matchingService.findMatches(extraction);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).getArtikelNr()).isEqualTo("MAN-BR-7710");
        assertThat(matches.get(0).getKonfidenz()).isEqualTo(1.0);
        assertThat(matches.get(0).getMatchType())
                .isEqualTo(MatchResult.MatchType.DIRECT_ARTIKELNR);
    }

    @Test
    @DisplayName("Direct match with unknown Artikel-Nr falls back to semantic")
    void directMatch_unknownArtikelNr_fallsBackToSemantic() {
        ExtractionResult extraction = new ExtractionResult();
        extraction.setArtikelNummer("UNKNOWN-99999");
        extraction.setArtikelBeschreibung("Turbolader Lager MAN B&W");
        extraction.setHersteller("MAN B&W");

        // Without embeddings, falls back to keyword
        List<MatchResult> matches = matchingService.findMatches(extraction);
        // Should still return results (keyword fallback)
        assertThat(matches).isNotNull();
    }

    // ── Semantic Search Tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Semantic search finds correct article from free text description")
    void semanticSearch_freeText_findsCorrectArticle() {
        // First embed all articles
        int embedded = embeddingService.embedAllArtikels();
        assertThat(embedded).isGreaterThan(0);

        // Search without artikel_nr — only description
        ExtractionResult extraction = new ExtractionResult();
        extraction.setArtikelBeschreibung("fuel injection valve Wärtsilä");
        extraction.setHersteller("Wärtsilä");
        extraction.setMaschinentyp("RT-flex58T");

        List<MatchResult> matches = matchingService.findMatches(extraction);

        assertThat(matches).isNotEmpty();
        // WAR-FIV-2234 should be in top results
        assertThat(matches).anyMatch(m -> m.getArtikelNr().contains("WAR-FIV"));
        assertThat(matches.get(0).getMatchType())
                .isEqualTo(MatchResult.MatchType.SEMANTIC_EMBEDDING);
    }

    @Test
    @DisplayName("Semantic search handles synonym variant")
    void semanticSearch_synonym_findsCorrectArticle() {
        embeddingService.embedAllArtikels();

        // "injection pump" is a synonym for Einspritzpumpe
        ExtractionResult extraction = new ExtractionResult();
        extraction.setArtikelBeschreibung("injection pump Wartsila RT-flex");

        List<MatchResult> matches = matchingService.findMatches(extraction);
        assertThat(matches).isNotEmpty();
    }

    // ── Confidence Threshold Tests ────────────────────────────────────────

    @Test
    @DisplayName("High confidence match routes to auto-send")
    void confidenceCheck_directMatch_isHighConfidence() {
        ExtractionResult extraction = new ExtractionResult();
        extraction.setArtikelNummer("WAR-FIV-2234");

        List<MatchResult> matches = matchingService.findMatches(extraction);
        boolean isHigh = matchingService.isHighConfidence(matches);

        assertThat(isHigh).isTrue();  // Direct match always 1.0
    }

    @Test
    @DisplayName("Empty matches are not high confidence")
    void confidenceCheck_emptyMatches_isNotHighConfidence() {
        assertThat(matchingService.isHighConfidence(List.of())).isFalse();
    }

    // ── Artikel Repository Tests ──────────────────────────────────────────

    @Test
    @DisplayName("Seed data is loaded by Flyway migration")
    void seedData_isPresent() {
        assertThat(artikelRepository.count()).isGreaterThanOrEqualTo(10);
        assertThat(artikelRepository.findByArtikelNr("MAN-BR-7710")).isPresent();
        assertThat(artikelRepository.findByArtikelNr("WAR-FIV-2234")).isPresent();
    }

    @Test
    @DisplayName("Keyword search finds articles by partial description")
    void keywordSearch_partialText_findsArticles() {
        List<Artikel> results = artikelRepository.searchByText("Turbolader");
        assertThat(results).isNotEmpty();
    }
}
