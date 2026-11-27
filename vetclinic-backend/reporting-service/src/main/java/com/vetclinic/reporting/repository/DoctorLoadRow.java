package com.vetclinic.reporting.repository;

import java.util.UUID;

/** Một dòng kết quả {@code VisitFactRepository.doctorLoads}. */
public record DoctorLoadRow(UUID doctorId, String doctorName, Long assigned, Long completed) {
}
