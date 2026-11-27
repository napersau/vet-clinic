package com.vetclinic.clinical.repository;

import java.util.UUID;
import com.vetclinic.clinical.entity.ImagingResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImagingResultRepository extends JpaRepository<ImagingResult, UUID> {
    List<ImagingResult> findByVisitServiceDetailIdIn(Collection<UUID> detailIds);
}
