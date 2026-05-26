package com.shipparts.repository;

import com.shipparts.domain.MatchFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MatchFeedbackRepository extends JpaRepository<MatchFeedback, UUID> {

    List<MatchFeedback> findByAnfrageId(UUID anfrageId);

    /** Load confirmed feedback since a given timestamp for nightly re-embedding */
    @Query("SELECT mf FROM MatchFeedback mf WHERE mf.bestaetigt = true AND mf.createdAt > :since")
    List<MatchFeedback> findConfirmedSince(@Param("since") Instant since);

    /** Feedback that has a human correction (different article chosen) */
    @Query("SELECT mf FROM MatchFeedback mf WHERE mf.korrektur IS NOT NULL AND mf.createdAt > :since")
    List<MatchFeedback> findWithCorrections(@Param("since") Instant since);
}
