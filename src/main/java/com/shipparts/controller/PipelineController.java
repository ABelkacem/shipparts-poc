package com.shipparts.controller;

import com.shipparts.domain.Anfrage;
import com.shipparts.domain.MatchFeedback;
import com.shipparts.dto.MatchResult;
import com.shipparts.repository.AnfrageRepository;
import com.shipparts.repository.MatchFeedbackRepository;
import com.shipparts.scheduler.ReEmbeddingScheduler;
import com.shipparts.service.PipelineOrchestrator;
import com.shipparts.service.embedding.EmbeddingService;
import com.shipparts.service.feedback.FeedbackService;
import com.shipparts.service.matching.MatchingService;
import com.shipparts.service.embedding.ExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API — exposes pipeline endpoints and the Human-Review interface.
 *
 * POST /api/pipeline/process          — trigger pipeline with email text + optional PDF
 * GET  /api/pipeline/queue            — list REVIEW_PENDING requests
 * POST /api/review/{feedbackId}/confirm   — confirm KI suggestion
 * POST /api/review/{feedbackId}/correct   — correct with different article
 * POST /api/review/{feedbackId}/reject    — reject request
 * POST /api/admin/reembed             — trigger manual re-embedding
 * POST /api/admin/reembed/full        — full index rebuild
 */
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineOrchestrator pipeline;
    private final AnfrageRepository anfrageRepository;
    private final MatchFeedbackRepository feedbackRepository;
    private final FeedbackService feedbackService;
    private final ReEmbeddingScheduler reEmbeddingScheduler;
    private final EmbeddingService embeddingService;
    private final com.shipparts.repository.ArtikelRepository artikelRepository;

    // ── Pipeline Trigger ──────────────────────────────────────────────────

    /**
     * Manually submit an email for pipeline processing.
     * Accepts multipart/form-data: emailBody (text) + optional pdf (file).
     *
     * Example:
     *   curl -X POST http://localhost:8080/api/pipeline/process \
     *     -F "from=captain@vessel.com" \
     *     -F "subject=Spare part request" \
     *     -F 'emailBody=We need 2x fuel injection valve MAN B&W RT-flex, P/N WAR-FIV-2234' \
     *     -F "pdf=@/path/to/attachment.pdf"
     */
    @PostMapping("/pipeline/process")
    public ResponseEntity<AnfrageResponse> processEmail(
            @RequestParam(defaultValue = "test@example.com") String from,
            @RequestParam(defaultValue = "Spare part inquiry") String subject,
            @RequestParam String emailBody,
            @RequestParam(required = false) MultipartFile pdf) throws Exception {

        byte[] pdfBytes = (pdf != null && !pdf.isEmpty()) ? pdf.getBytes() : null;
        Anfrage result  = pipeline.processEmail(from, subject, emailBody, pdfBytes);

        return ResponseEntity.ok(AnfrageResponse.from(result));
    }

    /**
     * Get all requests currently awaiting human review.
     */
    @GetMapping("/pipeline/queue")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ReviewQueueItem>> getReviewQueue() {
        List<Anfrage> pending = anfrageRepository
                .findByStatusOrderByCreatedAtDesc(Anfrage.AnfrageStatus.REVIEW_PENDING);

        List<ReviewQueueItem> items = pending.stream().map(a -> {
            List<MatchFeedback> feedbacks = feedbackRepository.findByAnfrageId(a.getId());
            return ReviewQueueItem.from(a, feedbacks);
        }).toList();

        return ResponseEntity.ok(items);
    }

    /**
     * Get detail of a single anfrage (for the review UI).
     */
    @GetMapping("/pipeline/{anfrageId}")
    @Transactional(readOnly = true)
    public ResponseEntity<ReviewQueueItem> getAnfrage(@PathVariable UUID anfrageId) {
        return anfrageRepository.findById(anfrageId)
                .map(a -> {
                    List<MatchFeedback> fb = feedbackRepository.findByAnfrageId(a.getId());
                    return ResponseEntity.ok(ReviewQueueItem.from(a, fb));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Human Review Actions ──────────────────────────────────────────────

    /**
     * Confirm the KI-suggested article (optionally with additional fields).
     *
     * Body: { "reviewer": "jane", "additionalFields": {"maschinentyp": "6S60MC"}, "notiz": "..." }
     */
    @PostMapping("/review/{feedbackId}/confirm")
    public ResponseEntity<MatchFeedback> confirmMatch(
            @PathVariable UUID feedbackId,
            @RequestBody ReviewAction action) {
        MatchFeedback result = feedbackService.confirm(
                feedbackId, action.reviewer(), action.additionalFields(), action.notiz());
        return ResponseEntity.ok(result);
    }

    /**
     * Correct with a different article.
     *
     * Body: { "reviewer": "jane", "correctArtikelId": "uuid", "additionalFields": {...}, "notiz": "..." }
     */
    @PostMapping("/review/{feedbackId}/correct")
    public ResponseEntity<MatchFeedback> correctMatch(
            @PathVariable UUID feedbackId,
            @RequestBody ReviewCorrection correction) {
        MatchFeedback result = feedbackService.correct(
                feedbackId,
                correction.correctArtikelId(),
                correction.reviewer(),
                correction.additionalFields(),
                correction.notiz());
        return ResponseEntity.ok(result);
    }

    /**
     * Reject the request.
     */
    @PostMapping("/review/{feedbackId}/reject")
    public ResponseEntity<MatchFeedback> rejectMatch(
            @PathVariable UUID feedbackId,
            @RequestBody ReviewAction action) {
        MatchFeedback result = feedbackService.reject(
                feedbackId, action.reviewer(), action.notiz());
        return ResponseEntity.ok(result);
    }

    // ── Admin ─────────────────────────────────────────────────────────────

    /** Trigger nightly re-embedding manually (e.g. after bulk ERP import). */
    @PostMapping("/admin/reembed")
    public ResponseEntity<Map<String, Object>> triggerReEmbed() {
        int count = reEmbeddingScheduler.triggerManual();
        return ResponseEntity.ok(Map.of("reembedded", count, "status", "OK"));
    }

    /** Full index rebuild of all articles. */
    @PostMapping("/admin/reembed/full")
    public ResponseEntity<Map<String, Object>> triggerFullReEmbed() {
        int count = embeddingService.embedAllArtikels();
        return ResponseEntity.ok(Map.of("reembedded", count, "status", "OK"));
    }

    /** Health + queue stats. */
    @GetMapping("/admin/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "pending_review",  anfrageRepository.countByStatus(Anfrage.AnfrageStatus.REVIEW_PENDING),
                "offer_sent",      anfrageRepository.countByStatus(Anfrage.AnfrageStatus.OFFER_SENT),
                "new",             anfrageRepository.countByStatus(Anfrage.AnfrageStatus.NEW),
                "rejected",        anfrageRepository.countByStatus(Anfrage.AnfrageStatus.REJECTED)
        ));
    }

    /** All articles — used by the review UI for the "correct to" dropdown. */
    @GetMapping("/artikel")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ArtikelOption>> listArtikel() {
        List<ArtikelOption> list = new java.util.ArrayList<>();
        artikelRepository.findAll().forEach(a -> list.add(new ArtikelOption(
                a.getId(), a.getArtikelNr(), a.getBeschreibungLang(), a.getHersteller())));
        return ResponseEntity.ok(list);
    }

    public record ArtikelOption(UUID id, String artikelNr, String beschreibung, String hersteller) {}

    // ── DTOs (records for brevity) ────────────────────────────────────────

    public record AnfrageResponse(
            UUID id, String status, String emailFrom,
            String emailSubject, String pdfType) {
        static AnfrageResponse from(Anfrage a) {
            return new AnfrageResponse(
                    a.getId(), a.getStatus().name(),
                    a.getEmailFrom(), a.getEmailSubject(), a.getPdfType());
        }
    }

    public record ReviewQueueItem(
            UUID anfrageId, String status, String emailFrom,
            String emailSubject, String emailBodyRaw,
            String pdfText, String pdfType,
            Map<String, Object> extraktJson,
            List<FeedbackSummary> feedbacks) {
        static ReviewQueueItem from(Anfrage a, List<MatchFeedback> fbs) {
            return new ReviewQueueItem(
                    a.getId(), a.getStatus().name(),
                    a.getEmailFrom(), a.getEmailSubject(),
                    a.getEmailBodyRaw(), a.getPdfText(), a.getPdfType(),
                    a.getExtraktJson(),
                    fbs.stream().map(FeedbackSummary::from).toList());
        }
    }

    public record FeedbackSummary(
            UUID feedbackId, String artikelNr,
            Double konfidenz, Boolean bestaetigt, String korrekturNr) {
        static FeedbackSummary from(MatchFeedback fb) {
            return new FeedbackSummary(
                    fb.getId(),
                    fb.getArtikel() != null ? fb.getArtikel().getArtikelNr() : null,
                    fb.getKonfidenz() != null ? fb.getKonfidenz().doubleValue() : null,
                    fb.getBestaetigt(),
                    fb.getKorrektur() != null ? fb.getKorrektur().getArtikelNr() : null);
        }
    }

    public record ReviewAction(
            String reviewer,
            Map<String, Object> additionalFields,
            String notiz) {}

    public record ReviewCorrection(
            String reviewer,
            UUID correctArtikelId,
            Map<String, Object> additionalFields,
            String notiz) {}
}
