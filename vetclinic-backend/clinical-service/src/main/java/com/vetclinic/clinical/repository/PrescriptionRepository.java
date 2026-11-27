package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.Prescription;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Prescription> findWithItemsByVisitId(UUID visitId);

    /** Bệnh án trọn đời: đơn thuốc của nhiều đợt khám trong một truy vấn (không N+1). */
    @EntityGraph(attributePaths = "items")
    List<Prescription> findWithItemsByVisitIdIn(Collection<UUID> visitIds);
}
