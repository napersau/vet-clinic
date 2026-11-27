package com.vetclinic.reporting.repository;

/** Một dòng kết quả {@code VisitFactRepository.countByStatus}. */
public record StatusCount(String status, Long total, Long emergency) {
}
