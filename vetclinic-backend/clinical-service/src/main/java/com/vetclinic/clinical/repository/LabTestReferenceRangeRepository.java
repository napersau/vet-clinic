package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.LabTestReferenceRange;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LabTestReferenceRangeRepository extends JpaRepository<LabTestReferenceRange, LabTestReferenceRange.Key> {

    Optional<LabTestReferenceRange> findByLabTestItemIdAndSpeciesId(Short labTestItemId, Short speciesId);

    @EntityGraph(attributePaths = "species")
    List<LabTestReferenceRange> findByLabTestItemIdIn(Collection<Short> labTestItemIds);
}
