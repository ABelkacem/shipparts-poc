package com.shipparts.service.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipparts.dto.ExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * KI #1 — LLM-based free-text extraction.
 * Extracts structured fields from raw email + PDF text using Mistral/Llama via Spring AI.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExtractionService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
        Du bist ein Extraktions-Assistent für eine Firma, die weltweit Ersatzteile für Schiffe verkauft.
        
        Deine Aufgabe: Extrahiere aus eingehenden Kundenanfragen (E-Mail-Text + ggf. PDF-Inhalt)
        strukturierte Daten im JSON-Format.
        
        Regeln:
        - Extrahiere NUR was explizit im Text steht. Nichts erfinden.
        - Falls ein Feld fehlt oder unklar ist, setze null.
        - Artikelnummern können alphanumerisch sein (z.B. "MAN-B&W 12345", "4T-5502-XXA").
        - Schiffsnamen beginnen meist mit "MV", "MT", "SS" oder stehen in Anführungszeichen.
        - Dringlichkeit "urgent" bei: urgent, ASAP, immediately, schnellstmöglich, dringend.
        - konfidenz_artikelnr: 0.0-1.0, wie sicher du die Artikelnummer erkannt hast.
        - Gib AUSSCHLIESSLICH gültiges JSON zurück, ohne Erklärung, ohne Markdown-Backticks.
        
        JSON-Schema (alle Felder exakt so benennen):
        {
          "artikel_nummer": null,
          "artikel_beschreibung": "",
          "menge": null,
          "einheit": null,
          "hersteller": null,
          "maschinentyp": null,
          "schiffsname": null,
          "hafen_lieferung": null,
          "dringlichkeit": null,
          "lieferdatum_gewuenscht": null,
          "konfidenz_artikelnr": null
        }
        """;

    /**
     * Extract structured fields from combined email + PDF text.
     *
     * @param emailBody raw email text
     * @param pdfText   extracted PDF text (may be empty)
     * @return structured ExtractionResult
     */
    public ExtractionResult extract(String emailBody, String pdfText) {
        String combinedText = buildCombinedText(emailBody, pdfText);
        log.debug("Extracting from text ({} chars)", combinedText.length());

        String userPrompt = """
                Extrahiere die Bestelldaten aus folgender Kundenanfrage:
                
                ---
                %s
                ---
                
                Antworte ausschliesslich mit dem JSON-Objekt, keine weiteren Erklärungen.
                """.formatted(combinedText);

        try {
            Prompt prompt = new Prompt(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userPrompt)
            );

            String rawJson = chatClient.prompt(prompt)
                    .call()
                    .content();

            log.debug("LLM raw response: {}", rawJson);
            return parseJson(rawJson);

        } catch (Exception e) {
            log.error("LLM extraction failed", e);
            return fallbackExtraction(emailBody);
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────

    private String buildCombinedText(String emailBody, String pdfText) {
        if (pdfText == null || pdfText.isBlank()) return emailBody;
        return emailBody + "\n\n--- PDF-Anhang ---\n" + pdfText;
    }

    private ExtractionResult parseJson(String raw) throws Exception {
        // Strip markdown code fences if model adds them anyway
        String clean = raw
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("```", "")
                .trim();

        // Find JSON object boundaries defensively
        int start = clean.indexOf('{');
        int end   = clean.lastIndexOf('}');
        if (start >= 0 && end > start) {
            clean = clean.substring(start, end + 1);
        }

        return objectMapper.readValue(clean, ExtractionResult.class);
    }

    /**
     * Minimal fallback when LLM is unavailable — returns empty result.
     * Pipeline will route to REVIEW_PENDING automatically.
     */
    private ExtractionResult fallbackExtraction(String emailBody) {
        log.warn("Using fallback extraction (LLM unavailable)");
        ExtractionResult result = new ExtractionResult();
        result.setArtikelBeschreibung(emailBody.length() > 200
                ? emailBody.substring(0, 200) : emailBody);
        return result;
    }
}
