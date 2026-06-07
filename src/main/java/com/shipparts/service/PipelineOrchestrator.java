package com.shipparts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipparts.domain.Anfrage;
import com.shipparts.domain.Anfrage.AnfrageStatus;
import com.shipparts.domain.Anfrage.Kanal;
import com.shipparts.dto.ProcurementAnfrage;
import com.shipparts.dto.ExtractionResult;
import com.shipparts.dto.MatchResult;
import com.shipparts.repository.AnfrageRepository;
import com.shipparts.service.embedding.ExtractionService;
import com.shipparts.service.feedback.FeedbackService;
import com.shipparts.service.matching.MatchingService;
import com.shipparts.service.offer.OfferService;
import com.shipparts.service.pdf.PdfScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pipeline Orchestrator — wires all services into the complete processing flow.
 *
 * Full pipeline:
 *   Email received
 *     → PDF scan (if attachment)
 *     → KI #1: LLM extraction (structured fields from free text)
 *     → Routing: artikel_nr present? → SQL direct / Semantic search
 *     → KI #2: Query embedding + pgvector similarity search
 *     → KI #3: Offer text generation
 *     → Confidence check:
 *         ≥ 0.85 → Auto-send (Dunkel-Prozess)
 *         < 0.85 → REVIEW_PENDING (Human-in-the-Loop)
 *     → Feedback stored → nightly re-embedding (KI #4)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final AnfrageRepository anfrageRepository;
    private final PdfScanService pdfScanService;
    private final ExtractionService extractionService;
    private final MatchingService matchingService;
    private final OfferService offerService;
    private final FeedbackService feedbackService;
    private final ObjectMapper objectMapper;

    @Value("${shipparts.offer.auto-send-enabled:false}")
    private boolean autoSendEnabled;

    // ── Entry point ───────────────────────────────────────────────────────

    /**
     * Process an incoming procurement request (from ERP/Einkaufssystem).
     * Transforms the structured request into the unified pipeline format (REQ-IN-02, REQ-IN-03).
     */
    @Transactional
    public Anfrage processProcurement(ProcurementAnfrage req) {
        log.info("Processing procurement request: bestellNr={} artikelNr={}",
                req.bestellNummer(), req.artikelNr());

        String from    = req.kontaktEmail() != null ? req.kontaktEmail() : "procurement@system.internal";
        String subject = "Procurement: " + (req.artikelNr() != null ? req.artikelNr() : req.beschreibung());
        String body    = buildProcurementBody(req);

        return runPipeline(Kanal.PROCUREMENT, from, subject, body, null);
    }

    /**
     * Process an incoming email (from IMAP listener or REST endpoint).
     * Returns the updated Anfrage entity with status set.
     */
    @Transactional
    public Anfrage processEmail(String from, String subject,
                                String body, byte[] pdfAttachment) {
        log.info("Processing email from={} subject='{}'", from, subject);
        return runPipeline(Kanal.EMAIL, from, subject, body, pdfAttachment);
    }

    // ── Unified internal pipeline ─────────────────────────────────────────

    @Transactional
    Anfrage runPipeline(Kanal kanal, String from, String subject,
                        String body, byte[] pdfAttachment) {
        // 1. Persist incoming request
        Anfrage anfrage = createAnfrage(from, subject, body, kanal);

        try {
            // 2. PDF scan (if attachment present)
            String pdfText = "";
            if (pdfAttachment != null && pdfAttachment.length > 0) {
                updateStatus(anfrage, AnfrageStatus.EXTRACTING);
                PdfScanService.PdfExtractionResult pdfResult =
                        pdfScanService.extractText(pdfAttachment);
                pdfText = pdfResult.text();
                anfrage.setPdfText(pdfText);
                anfrage.setPdfType(pdfResult.type().name());
                log.info("PDF extracted: type={}, chars={}", pdfResult.type(), pdfText.length());
            }

            // 3. KI #1 — LLM Extraction
            updateStatus(anfrage, AnfrageStatus.EXTRACTING);
            ExtractionResult extraction = extractionService.extract(body, pdfText);
            anfrage.setExtraktJson(objectMapper.convertValue(extraction, Map.class));
            log.info("Extraction: artikelNr={}, beschreibung={}, konfidenz={}",
                    extraction.getArtikelNummer(),
                    extraction.getArtikelBeschreibung(),
                    extraction.getKonfidenzArtikelnr());

            // 4. Matching (SQL direct OR semantic)
            updateStatus(anfrage, AnfrageStatus.MATCHING);
            List<MatchResult> matches = matchingService.findMatches(extraction);

            if (matches.isEmpty()) {
                log.warn("No matches found for anfrage={}", anfrage.getId());
                updateStatus(anfrage, AnfrageStatus.REVIEW_PENDING);
                saveFeedbackAndReturn(anfrage, null, 0.0);
                return anfrageRepository.save(anfrage);
            }

            MatchResult best = matches.get(0);
            log.info("Best match: artikel={}, type={}, konfidenz={:.3f}",
                    best.getArtikelNr(), best.getMatchType(), best.getKonfidenz());

            // 5. KI #3 — Generate offer text
            String offerText = offerService.generateOfferText(extraction, matches);

            // 6. Confidence routing
            boolean highConfidence = matchingService.isHighConfidence(matches);

            if (highConfidence && autoSendEnabled) {
                // ── DUNKEL-PROZESS: Auto-send ──────────────────────────
                log.info("High confidence ({:.2f}) — auto-sending offer", best.getKonfidenz());
                sendOffer(anfrage, offerText, from);
                updateStatus(anfrage, AnfrageStatus.OFFER_SENT);
                // Auto-confirmed feedback
                var fb = feedbackService.saveSuggestion(anfrage, best.getArtikelId(), best.getKonfidenz());
                feedbackService.confirm(fb.getId(), "AUTO", null, "Auto-confirmed by pipeline");

            } else {
                // ── HUMAN-IN-THE-LOOP: Queue for review ───────────────
                log.info("Low confidence ({:.2f}) or auto-send disabled — queuing for review",
                        best.getKonfidenz());
                updateStatus(anfrage, AnfrageStatus.REVIEW_PENDING);
                feedbackService.saveSuggestion(anfrage, best.getArtikelId(), best.getKonfidenz());
            }

        } catch (Exception e) {
            log.error("Pipeline error for anfrage={}: {}", anfrage.getId(), e.getMessage(), e);
            updateStatus(anfrage, AnfrageStatus.REVIEW_PENDING);
        }

        return anfrageRepository.save(anfrage);
    }

    // ── Private Helpers ───────────────────────────────────────────────────

    private Anfrage createAnfrage(String from, String subject, String body, Kanal kanal) {
        Anfrage a = Anfrage.builder()
                .emailFrom(from)
                .emailSubject(subject)
                .emailBodyRaw(body)
                .kanal(kanal)
                .status(AnfrageStatus.NEW)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return anfrageRepository.save(a);
    }

    private String buildProcurementBody(ProcurementAnfrage req) {
        StringBuilder sb = new StringBuilder();
        sb.append("Procurement Request\n");
        if (req.bestellNummer() != null) sb.append("PO-Nr: ").append(req.bestellNummer()).append("\n");
        if (req.system()        != null) sb.append("System: ").append(req.system()).append("\n");
        if (req.artikelNr()     != null) sb.append("Artikel-Nr: ").append(req.artikelNr()).append("\n");
        if (req.beschreibung()  != null) sb.append("Beschreibung: ").append(req.beschreibung()).append("\n");
        if (req.hersteller()    != null) sb.append("Hersteller: ").append(req.hersteller()).append("\n");
        if (req.maschinentyp()  != null) sb.append("Maschinentyp: ").append(req.maschinentyp()).append("\n");
        if (req.menge()         != null) sb.append("Menge: ").append(req.menge()).append("\n");
        if (req.lieferadresse() != null) sb.append("Lieferadresse: ").append(req.lieferadresse()).append("\n");
        if (req.anforderungsText() != null) sb.append("\n").append(req.anforderungsText());
        return sb.toString();
    }

    private void updateStatus(Anfrage anfrage, AnfrageStatus status) {
        anfrage.setStatus(status);
        anfrage.setUpdatedAt(Instant.now());
        anfrageRepository.save(anfrage);
    }

    private void saveFeedbackAndReturn(Anfrage anfrage, java.util.UUID artikelId, double score) {
        feedbackService.saveSuggestion(anfrage, artikelId, score);
    }

    private void sendOffer(Anfrage anfrage, String offerText, String to) {
        // In production: inject JavaMailSender and send actual email
        // For POC: log only
        log.info("=== OFFER SENT TO {} ===\n{}", to, offerText);
    }
}
