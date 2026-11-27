package com.vetclinic.notification.client;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.notification.dto.VisitSummary;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Endpoint nội bộ của clinical-service (không cần header người dùng, Gateway không định tuyến ra ngoài). */
@FeignClient(name = "clinical-service", path = "/internal/pet-visits")
public interface ClinicalClient {

    @GetMapping("/{id}/summary")
    ApiResponse<VisitSummary> getVisit(@PathVariable("id") UUID id);
}
