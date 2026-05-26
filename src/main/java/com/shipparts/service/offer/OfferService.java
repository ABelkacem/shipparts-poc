package com.shipparts.service.offer;

import com.shipparts.domain.Anfrage;
import com.shipparts.domain.Angebot;
import com.shipparts.dto.ExtractionResult;
import com.shipparts.dto.MatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * KI #3 — Offer Generation Service.
 *
 * Takes the best match(es) from the database and generates a
 * professional offer email text in English using the LLM.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OfferService {

    private final ChatClient chatClient;

    @Value("${shipparts.offer.from-address:quotes@example.com}")
    private String fromAddress;

    private static final String SYSTEM_PROMPT = """
        You are a professional maritime spare parts sales agent.
        Write concise, professional offer emails in English.
        Always include: part details, unit price, availability, lead time, and a call to action.
        Use a friendly but formal tone. Keep it under 200 words.
        Output ONLY the email body text, no subject line, no salutation header.
        """;

    /**
     * Generate offer text from the best match and extracted request data.
     */
    public String generateOfferText(ExtractionResult extraction, List<MatchResult> matches) {
        if (matches.isEmpty()) return buildNoMatchText(extraction);

        MatchResult best = matches.get(0);
        String context   = buildContext(extraction, best);

        log.debug("Generating offer for artikel={}", best.getArtikelNr());

        try {
            Prompt prompt = new Prompt(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("Generate a quote offer based on this context:\n\n" + context)
            );
            return chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            log.error("LLM offer generation failed, using template fallback", e);
            return buildTemplateFallback(extraction, best);
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────

    private String buildContext(ExtractionResult e, MatchResult m) {
        return """
            Customer Request:
            - Description: %s
            - Manufacturer: %s
            - Machine type: %s
            - Ship: %s
            - Delivery port: %s
            - Urgency: %s
            
            Matched Article:
            - Article No: %s
            - Description: %s
            - Unit price: EUR %.2f
            - Stock: %d units available
            - Lead time: %d days
            - Match confidence: %.0f%%
            """.formatted(
                    nvl(e.getArtikelBeschreibung()),
                    nvl(e.getHersteller()),
                    nvl(e.getMaschinentyp()),
                    nvl(e.getSchiffsname()),
                    nvl(e.getHafenLieferung()),
                    nvl(e.getDringlichkeit()),
                    m.getArtikelNr(),
                    m.getBeschreibung(),
                    m.getPreisEur(),
                    m.getBestand(),
                    m.getLieferzeitTage() != null ? m.getLieferzeitTage() : 7,
                    m.getKonfidenz() * 100
        );
    }

    /** Template fallback when LLM is unavailable */
    private String buildTemplateFallback(ExtractionResult e, MatchResult m) {
        return """
            Dear Customer,
            
            Thank you for your inquiry. We are pleased to offer the following:
            
            Article No.:  %s
            Description:  %s
            Unit Price:   EUR %.2f
            Availability: %d units in stock
            Lead Time:    %d working days
            
            %sPlease confirm your order and delivery address to proceed.
            
            Best regards,
            Sales Team
            """.formatted(
                m.getArtikelNr(),
                m.getBeschreibung(),
                m.getPreisEur(),
                m.getBestand(),
                m.getLieferzeitTage() != null ? m.getLieferzeitTage() : 7,
                e.isUrgent() ? "⚡ Priority handling applied for urgent requests.\n\n" : ""
        );
    }

    private String buildNoMatchText(ExtractionResult e) {
        return """
            Dear Customer,
            
            Thank you for your inquiry regarding: %s
            
            We were unable to automatically identify a matching part in our catalog.
            Our team will review your request manually and get back to you shortly.
            
            Best regards,
            Sales Team
            """.formatted(nvl(e.getArtikelBeschreibung()));
    }

    private String nvl(String s) { return s != null ? s : "N/A"; }
}
