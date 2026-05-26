package com.shipparts.service.feedback;

import com.shipparts.domain.*;
import com.shipparts.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Feedback Service — Human-in-the-Loop persistence.
 *
 * Records every human decision (confirm / correct / reject) and
 * updates article synonyms so the feedback loop improves the index.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedbackService {

    private final MatchFeedbackRepository feedbackRepository;
    private final AnfrageRepository anfrageRepository;
    private final ArtikelRepository artikelRepository;

    // ── Save Initial KI Suggestion ────────────────────────────────────────

    /**
     * Called by the pipeline after matching, before sending to review.
     * Records the KI suggestion for later comparison.
     */
    @Transactional
    public MatchFeedback saveSuggestion(Anfrage anfrage,
                                        UUID artikelId,
                                        double konfidenz) {
        Artikel artikel = artikelId != null
                ? artikelRepository.findById(artikelId).orElse(null)
                : null;

        MatchFeedback feedback = MatchFeedback.builder()
                .anfrage(anfrage)
                .artikel(artikel)
                .konfidenz(BigDecimal.valueOf(konfidenz))
                .bestaetigt(false)
                .createdAt(Instant.now())
                .build();

        return feedbackRepository.save(feedback);
    }

    // ── Human Review Actions ──────────────────────────────────────────────

    /**
     * Human confirms the KI suggestion — article is correct.
     * Marks as bestaetigt=true; no correction needed.
     */
    @Transactional
    public MatchFeedback confirm(UUID feedbackId,
                                 String reviewer,
                                 Map<String, Object> additionalFields,
                                 String notiz) {
        MatchFeedback fb = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + feedbackId));

        fb.setBestaetigt(true);
        fb.setReviewer(reviewer);
        fb.setMerkmaleErgaenzt(additionalFields);
        fb.setInterneNotiz(notiz);

        // Update article synonyms with any additional text from the request
        if (fb.getArtikel() != null && additionalFields != null) {
            applyAdditionalFields(fb.getArtikel(), additionalFields);
        }

        // Update anfrage status
        updateAnfrageStatus(fb.getAnfrage().getId(), Anfrage.AnfrageStatus.OFFER_SENT);

        log.info("Feedback confirmed: anfrage={}, artikel={}",
                fb.getAnfrage().getId(), fb.getArtikel() != null ? fb.getArtikel().getArtikelNr() : "none");
        return feedbackRepository.save(fb);
    }

    /**
     * Human corrects the KI suggestion — different article should be used.
     * Records korrektur_id for re-embedding improvement.
     */
    @Transactional
    public MatchFeedback correct(UUID feedbackId,
                                 UUID correctArtikelId,
                                 String reviewer,
                                 Map<String, Object> additionalFields,
                                 String notiz) {
        MatchFeedback fb = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + feedbackId));

        Artikel correctArtikel = artikelRepository.findById(correctArtikelId)
                .orElseThrow(() -> new IllegalArgumentException("Artikel not found: " + correctArtikelId));

        fb.setBestaetigt(true);
        fb.setKorrektur(correctArtikel);
        fb.setReviewer(reviewer);
        fb.setMerkmaleErgaenzt(additionalFields);
        fb.setInterneNotiz(notiz);

        // Store the freitext → correct article mapping for re-embedding
        if (additionalFields != null) {
            applyAdditionalFields(correctArtikel, additionalFields);
        }

        // Add the search query text as a synonym on the correct article
        if (fb.getAnfrage().getExtraktJson() != null) {
            Object beschreibung = fb.getAnfrage().getExtraktJson().get("artikel_beschreibung");
            if (beschreibung instanceof String s && !s.isBlank()) {
                addSynonym(correctArtikel, s);
            }
        }

        updateAnfrageStatus(fb.getAnfrage().getId(), Anfrage.AnfrageStatus.OFFER_SENT);

        log.info("Feedback corrected: anfrage={}, ki={} → correct={}",
                fb.getAnfrage().getId(),
                fb.getArtikel() != null ? fb.getArtikel().getArtikelNr() : "none",
                correctArtikel.getArtikelNr());
        return feedbackRepository.save(fb);
    }

    /**
     * Human rejects the request entirely (e.g. spam, out of scope).
     */
    @Transactional
    public MatchFeedback reject(UUID feedbackId, String reviewer, String notiz) {
        MatchFeedback fb = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback not found: " + feedbackId));

        fb.setBestaetigt(false);
        fb.setReviewer(reviewer);
        fb.setInterneNotiz(notiz);
        updateAnfrageStatus(fb.getAnfrage().getId(), Anfrage.AnfrageStatus.REJECTED);

        log.info("Feedback rejected: anfrage={}", fb.getAnfrage().getId());
        return feedbackRepository.save(fb);
    }

    // ── Private Helpers ───────────────────────────────────────────────────

    private void applyAdditionalFields(Artikel artikel, Map<String, Object> fields) {
        // Extract maschinentyp if provided
        if (fields.containsKey("maschinentyp") && artikel.getMaschinentyp() == null) {
            artikel.setMaschinentyp((String) fields.get("maschinentyp"));
        }
        // Extract synonym text
        if (fields.containsKey("synonym")) {
            addSynonym(artikel, (String) fields.get("synonym"));
        }
        artikelRepository.save(artikel);
    }

    private void addSynonym(Artikel artikel, String synonym) {
        java.util.List<String> synonyme = new java.util.ArrayList<>(
                artikel.getSynonyme() != null ? artikel.getSynonyme() : java.util.List.of()
        );
        if (!synonyme.contains(synonym)) {
            synonyme.add(synonym);
            artikel.setSynonyme(synonyme);
            artikelRepository.save(artikel);
            log.debug("Added synonym '{}' to artikel {}", synonym, artikel.getArtikelNr());
        }
    }

    private void updateAnfrageStatus(UUID anfrageId, Anfrage.AnfrageStatus status) {
        anfrageRepository.findById(anfrageId).ifPresent(a -> {
            a.setStatus(status);
            a.setUpdatedAt(Instant.now());
            anfrageRepository.save(a);
        });
    }
}
