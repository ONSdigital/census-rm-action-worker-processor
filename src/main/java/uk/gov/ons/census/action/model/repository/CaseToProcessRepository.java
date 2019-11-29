package uk.gov.ons.census.action.model.repository;

import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gov.ons.census.action.model.entity.CaseToProcess;

public interface CaseToProcessRepository extends JpaRepository<CaseToProcess, UUID> {

  @Query(
      value = "SELECT * FROM actionv2.case_to_process LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  Stream<CaseToProcess> findChunkToProcess(@Param("limit") int limit);
}
