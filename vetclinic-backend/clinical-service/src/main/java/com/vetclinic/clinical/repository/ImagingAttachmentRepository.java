package com.vetclinic.clinical.repository;

import java.util.UUID;
import com.vetclinic.clinical.entity.ImagingAttachment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImagingAttachmentRepository extends JpaRepository<ImagingAttachment, UUID> {
    List<ImagingAttachment> findByImagingResultIdIn(Collection<UUID> imagingResultIds);
}
