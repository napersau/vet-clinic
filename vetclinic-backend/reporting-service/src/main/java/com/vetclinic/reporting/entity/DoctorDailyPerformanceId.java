package com.vetclinic.reporting.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/** Khóa chính (business_date, doctor_id) của {@link DoctorDailyPerformance}. */
public record DoctorDailyPerformanceId(LocalDate businessDate, UUID doctorId) implements Serializable {
}
