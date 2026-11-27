package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.dto.response.PetSummaryResponse;
import com.vetclinic.clinical.service.PetService;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoint cho các service khác gọi qua OpenFeign (Kiến trúc v4.0 §5.1). Nằm dưới /internal/**: không cần header người dùng và Gateway
 * không định tuyến ra ngoài, nên chỉ truy cập được trong mạng Docker.
 */
@RestController
@RequestMapping("/internal/pets")
@RequiredArgsConstructor
public class InternalClinicalController {

    private final PetService petService;

    /**
     * appointment-service kiểm tra thú cưng tồn tại, thuộc đúng chủ nuôi khi đặt lịch và lấy tên thú cưng + tên / SĐT chủ để chụp snapshot.
     * Thú cưng đã xóa mềm trả 404 PET_NOT_FOUND. (Danh sách lịch hẹn của chủ nuôi nay lọc bằng {@code appointments.owner_id}, nên
     * không còn endpoint tra thú cưng theo chủ hay theo lô.)
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PetSummaryResponse>> pet(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(petService.getSummary(id)));
    }
}
