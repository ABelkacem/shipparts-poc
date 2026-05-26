package com.shipparts.service.email;

import com.shipparts.service.PipelineOrchestrator;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Properties;

/**
 * Email Listener — polls IMAP inbox and hands emails to the pipeline.
 *
 * Polls every 60 seconds (configurable via shipparts.email.poll-interval-ms).
 * Marks messages as SEEN after successful processing.
 * Supports both plain text and multipart emails with PDF attachments.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailListenerService {

    private final PipelineOrchestrator pipeline = new PipelineOrchestrator();

    @Value("${shipparts.email.host:imap.example.com}")
    private String host;

    @Value("${shipparts.email.port:993}")
    private int port;

    @Value("${shipparts.email.username:orders@example.com}")
    private String username;

    @Value("${shipparts.email.password:secret}")
    private String password;

    @Value("${shipparts.email.folder:INBOX}")
    private String folder;

    /**
     * Poll IMAP inbox every 60 seconds for new UNSEEN messages.
     * In production, replace with Jakarta Mail IDLE listener for real-time.
     */
    @Scheduled(fixedDelayString = "${shipparts.email.poll-interval-ms:60000}")
    public void pollInbox() {
        if (host.equals("imap.example.com")) {
            log.debug("Email polling skipped (no real IMAP configured)");
            return;
        }

        log.debug("Polling IMAP inbox: {}@{}", username, host);
        Store store = null;
        Folder inbox = null;

        try {
            store = connectToStore();
            inbox = store.getFolder(folder);
            inbox.open(Folder.READ_WRITE);

            // Only process UNSEEN messages
            Message[] messages = inbox.search(
                    new jakarta.mail.search.FlagTerm(
                            new Flags(Flags.Flag.SEEN), false));

            log.info("Found {} new messages", messages.length);

            for (Message message : messages) {
                try {
                    processMessage(message);
                    // Mark as read after successful processing
                    message.setFlag(Flags.Flag.SEEN, true);
                } catch (Exception e) {
                    log.error("Failed to process message '{}': {}",
                            message.getSubject(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("IMAP polling failed: {}", e.getMessage());
        } finally {
            closeQuietly(inbox, store);
        }
    }

    // ── Message Processing ────────────────────────────────────────────────

    private void processMessage(Message message) throws MessagingException, IOException {
        String from    = message.getFrom() != null ? message.getFrom()[0].toString() : "unknown";
        String subject = message.getSubject() != null ? message.getSubject() : "";
        String body    = "";
        byte[] pdfBytes = null;

        // Handle multipart (body + attachments) vs plain text
        if (message.isMimeType("text/plain")) {
            body = (String) message.getContent();
        } else if (message.isMimeType("multipart/*")) {
            MimeMultipart multipart = (MimeMultipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
                    // PDF attachment
                    if (part.getContentType().toLowerCase().contains("pdf")) {
                        pdfBytes = part.getInputStream().readAllBytes();
                        log.debug("PDF attachment found: {} bytes", pdfBytes.length);
                    }
                } else if (part.isMimeType("text/plain")) {
                    body = (String) part.getContent();
                } else if (part.isMimeType("text/html") && body.isEmpty()) {
                    // Fallback to HTML body stripped of tags
                    body = ((String) part.getContent()).replaceAll("<[^>]+>", " ").trim();
                }
            }
        }

        log.info("Processing email: from={}, subject={}, pdf={}",
                from, subject, pdfBytes != null ? pdfBytes.length + " bytes" : "none");

        pipeline.processEmail(from, subject, body, pdfBytes);
    }

    // ── IMAP Connection ───────────────────────────────────────────────────

    private Store connectToStore() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.timeout", "10000");

        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(host, port, username, password);
        return store;
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) {}
        try { if (store  != null && store.isConnected())  store.close();  } catch (Exception ignored) {}
    }
}
