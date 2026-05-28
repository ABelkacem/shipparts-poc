package com.shipparts.service.pdf;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF processing service.
 *
 * Handles four PDF types:
 *   1. TEXT_PDF   – digital PDF with embedded text (pdfbox direct)
 *   2. SCANNED    – raster scan, no text → OCR via Tesseract (external process)
 *   3. FORM       – fillable form fields (pdfbox AcroForm)
 *   4. IMAGE      – technical drawings, type plates (PyMuPDF/Vision not in POC)
 *
 * In production: SCANNED type calls an OCR microservice or Tesseract directly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PdfScanService {

    /** Minimum character count to classify a PDF as TEXT (vs scanned) */
    private static final int MIN_TEXT_LENGTH = 50;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Main entry point: detect PDF type and extract text accordingly.
     */
    public PdfExtractionResult extractText(byte[] pdfBytes) {
        try {
            PdfType type = detectType(pdfBytes);
            log.debug("PDF type detected: {}", type);

            String text = switch (type) {
                case TEXT_PDF -> extractFromTextPdf(pdfBytes);
                case FORM     -> extractFromForm(pdfBytes);
                case SCANNED  -> extractWithOcr(pdfBytes);
                case IMAGE    -> "[Technische Zeichnung – manuelle Prüfung erforderlich]";
                case NONE     -> "";
            };

            return new PdfExtractionResult(type, text.trim());

        } catch (Exception e) {
            log.error("PDF extraction failed", e);
            return new PdfExtractionResult(PdfType.NONE, "");
        }
    }

    // ── Type Detection ────────────────────────────────────────────────────

    /**
     * Detect PDF type by attempting text extraction.
     * If less than MIN_TEXT_LENGTH chars found → likely scanned.
     */
    public PdfType detectType(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {

            // Check for fillable form fields first
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm != null && !acroForm.getFields().isEmpty()) {
                return PdfType.FORM;
            }

            // Try text extraction
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);

            if (text == null || text.strip().length() < MIN_TEXT_LENGTH) {
                // Very little text found → check if images exist (scanned)
                boolean hasImages = false;
                for (var page : doc.getPages()) {
                    if (page.getResources().getXObjectNames().iterator().hasNext()) {
                        hasImages = true;
                        break;
                    }
                }
                return hasImages ? PdfType.SCANNED : PdfType.IMAGE;
            }

            return PdfType.TEXT_PDF;
        }
    }

    // ── Extraction Strategies ─────────────────────────────────────────────

    /**
     * Strategy 1: Direct text extraction from digital PDF.
     */
    private String extractFromTextPdf(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // better for multi-column layouts
            String text = stripper.getText(doc);
            log.debug("Text PDF: extracted {} chars from {} pages",
                    text.length(), doc.getNumberOfPages());
            return text;
        }
    }

    /**
     * Strategy 2: Extract fillable form field values.
     * Useful for standardized order forms (Bestellformulare).
     */
    private String extractFromForm(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) return extractFromTextPdf(pdfBytes);

            StringBuilder sb = new StringBuilder();
            for (PDField field : acroForm.getFieldTree()) {
                String name  = field.getFullyQualifiedName();
                String value = field.getValueAsString();
                if (value != null && !value.isBlank()) {
                    sb.append(name).append(": ").append(value).append("\n");
                }
            }

            // Also extract visible text (label text around fields)
            PDFTextStripper stripper = new PDFTextStripper();
            sb.append("\n--- Dokumenttext ---\n");
            sb.append(stripper.getText(doc));

            int fieldCount = 0;
            for (PDField ignored : acroForm.getFieldTree()) fieldCount++;
            log.debug("Form PDF: extracted {} form fields", fieldCount);
            return sb.toString();
        }
    }

    /**
     * Strategy 3: OCR for scanned PDFs.
     *
     * POC implementation: calls system Tesseract via Process.
     * Production: use Tesseract Java binding (tess4j) or dedicated OCR microservice.
     *
     * Requires: tesseract-ocr + pdftoppm installed on host.
     */
    private String extractWithOcr(byte[] pdfBytes) {
        log.info("Scanned PDF detected – attempting OCR via Tesseract");

        try {
            // Write PDF to temp file
            java.nio.file.Path tmpPdf = java.nio.file.Files.createTempFile("scan_", ".pdf");
            java.nio.file.Path tmpOut = java.nio.file.Files.createTempDirectory("ocr_out_");
            java.nio.file.Files.write(tmpPdf, pdfBytes);

            // Step 1: Rasterize PDF pages to images (300 DPI for good OCR quality)
            ProcessBuilder pb = new ProcessBuilder(
                    "pdftoppm", "-jpeg", "-r", "300",
                    tmpPdf.toString(),
                    tmpOut.resolve("page").toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

            // Step 2: Run Tesseract on each page image
            StringBuilder ocrText = new StringBuilder();
            try (var stream = java.nio.file.Files.list(tmpOut)) {
                List<java.nio.file.Path> pages = stream
                        .filter(p -> p.toString().endsWith(".jpg"))
                        .sorted()
                        .toList();

                for (java.nio.file.Path pageImg : pages) {
                    ProcessBuilder tessPb = new ProcessBuilder(
                            "tesseract", pageImg.toString(), "stdout",
                            "-l", "deu+eng",       // German + English
                            "--psm", "3"           // Fully automatic page segmentation
                    );
                    tessPb.redirectErrorStream(true);
                    Process tessProc = tessPb.start();
                    String pageText = new String(tessProc.getInputStream().readAllBytes());
                    tessProc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                    ocrText.append(pageText).append("\n");
                }
            }

            // Cleanup
            java.nio.file.Files.deleteIfExists(tmpPdf);

            String result = ocrText.toString();
            log.debug("OCR extracted {} chars", result.length());
            return result.isBlank()
                    ? "[OCR konnte keinen Text extrahieren – manuelle Prüfung erforderlich]"
                    : result;

        } catch (Exception e) {
            log.warn("OCR failed (Tesseract may not be installed): {}", e.getMessage());
            // Graceful degradation: return placeholder for human review
            return "[Gescanntes Dokument – OCR nicht verfügbar. Bitte manuell prüfen.]";
        }
    }

    // ── Types ─────────────────────────────────────────────────────────────

    public enum PdfType {
        TEXT_PDF, SCANNED, FORM, IMAGE, NONE
    }

    public record PdfExtractionResult(PdfType type, String text) {
        public boolean hasText() {
            return text != null && !text.isBlank()
                    && !text.startsWith("[");
        }
    }
}
