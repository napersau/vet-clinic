package com.vetclinic.clinical.client;

import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;

/** Endpoint nội bộ của auth-service (không cần header người dùng, Gateway không định tuyến ra ngoài). */
@FeignClient(name = "auth-service", path = "/internal/users")
public interface AuthClient {

    @GetMapping("/{id}")
    ApiResponse<UserSummaryResponse> getUser(@PathVariable("id") UUID id);

    /** Tra cứu hàng loạt theo ?ids=a,b,c (hiển thị tên bác sĩ trong bệnh án). */
    @GetMapping
    ApiResponse<List<UserSummaryResponse>> getUsers(@RequestParam("ids") List<UUID> ids);

    /** Bác sĩ (VETERINARIAN) đang ACTIVE kèm số phòng cố định. */
    @GetMapping("/doctors")
    ApiResponse<List<UserSummaryResponse>> getActiveDoctors();
}
