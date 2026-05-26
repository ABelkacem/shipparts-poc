package com.shipparts.repository;

import com.shipparts.domain.Artikel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ArtikelRepository extends JpaRepository<Artikel, UUID> {

    Optional<Artikel> findByArtikelNr(String artikelNr);

    List<Artikel> findByHersteller(String hersteller);

    /** Full-text search on beschreibung for keyword fallback */
    @Query("SELECT a FROM Artikel a WHERE " +
           "LOWER(a.beschreibungLang) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(a.beschreibungKurz) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "AND a.bestand > 0")
    List<Artikel> searchByText(@Param("query") String query);
}
