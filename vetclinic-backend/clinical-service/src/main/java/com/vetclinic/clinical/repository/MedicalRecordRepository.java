package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.MedicalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Khóa chính của hồ sơ khám chính là visit_id (1-1 với đợt khám). */
@Repository
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, UUID> {
}
