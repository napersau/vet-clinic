package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.VitalSign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface VitalSignRepository extends JpaRepository<VitalSign, Long> {

    List<VitalSign> findByVisitIdOrderByRecordedAtAscIdAsc(UUID visitId);

    List<VitalSign> findByVisitIdInOrderByRecordedAtAscIdAsc(Collection<UUID> visitIds);

    /** T04 / chốt ca: đã có ít nhất một lần đo ghi đủ cả cân nặng lẫn thân nhiệt (CLIN_1308). */
    @Query("select count(s) > 0 from VitalSign s where s.visitId = :visitId and s.weightKg is not null and s.temperatureC is not null")
    boolean existsCompleteMeasurement(@Param("visitId") UUID visitId);
}
