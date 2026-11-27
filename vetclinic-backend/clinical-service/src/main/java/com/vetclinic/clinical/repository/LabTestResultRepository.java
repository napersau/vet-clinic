package com.vetclinic.clinical.repository;

import java.util.UUID;
import com.vetclinic.clinical.entity.LabTestResult;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface LabTestResultRepository extends JpaRepository<LabTestResult, Long> {

    @EntityGraph(attributePaths = "labTestItem")
    List<LabTestResult> findByVisitServiceDetailIdInOrderByIdAsc(Collection<UUID> detailIds);
}
