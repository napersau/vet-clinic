package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.entity.MedicalRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Hồ sơ khám chuyên môn của một đợt khám. {@code locked}: đã chốt chuyên môn, không còn sửa được (CLIN_1317). */
public record MedicalRecordResponse(
        UUID visitId,
        String anamnesis,
        String clinicalFindings,
        String preliminaryDiagnosis,
        String finalDiagnosis,
        String treatmentPlan,
        String doctorAdvice,
        LocalDate followUpDate,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        boolean locked
) {
    public static MedicalRecordResponse of(MedicalRecord r, boolean locked) {
        return new MedicalRecordResponse(r.getVisitId(), r.getAnamnesis(), r.getClinicalFindings(), r.getPreliminaryDiagnosis(),
                r.getFinalDiagnosis(), r.getTreatmentPlan(), r.getDoctorAdvice(), r.getFollowUpDate(), r.getCreatedAt(),
                r.getUpdatedAt(), r.getVersion(), locked);
    }
}
