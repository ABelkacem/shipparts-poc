package com.shipparts.repository;

import com.shipparts.domain.Anfrage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnfrageRepository extends JpaRepository<Anfrage, UUID> {
    List<Anfrage> findByStatusOrderByCreatedAtDesc(Anfrage.AnfrageStatus status);
    long countByStatus(Anfrage.AnfrageStatus status);
}
