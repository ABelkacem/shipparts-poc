package com.shipparts.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "angebot")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Angebot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anfrage_id", nullable = false)
    private Anfrage anfrage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artikel_id")
    private Artikel artikel;

    @Column(name = "angebot_text", columnDefinition = "TEXT")
    private String angebotText;

    @Column(precision = 5, scale = 4)
    private BigDecimal konfidenz;

    @Column(name = "auto_sent")
    private Boolean autoSent = false;

    @Column(name = "versandt_am")
    private Instant versandtAm;

    @Column(name = "created_at")
    private Instant createdAt;
}
