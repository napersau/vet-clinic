package com.vetclinic.clinical.controller;

import java.util.UUID;
import com.vetclinic.clinical.dto.response.VisitSummaryResponse;
import com.vetclinic.clinical.service.VisitService;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Đường dẫn nội bộ theo Kiến trúc v3.0 §5.1: billing-pharmacy-service đọc trạng thái + bác sĩ phụ trách của đợt khám (kê đơn, lập hóa đơn
 * thuốc). Chỉ ĐỌC. Không cần header người dùng, Gateway không định tuyến ra ngoài.
 */
@RestController
@RequestMapping("/internal/pet-visits")
@RequiredArgsConstructor
public class InternalVisitController {

    private final VisitService visitService;

    @GetMapping("/{id}/summary")
    public ResponseEntity<ApiResponse<VisitSummaryResponse>> summary(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(visitService.summary(id)));
    }

    /** Theo lô, để billing làm giàu danh sách đơn thuốc bằng mã ca khám / tên thú cưng; id không tồn tại bị bỏ qua. */
    @GetMapping("/summaries")
    public ResponseEntity<ApiResponse<List<VisitSummaryResponse>>> summaries(@RequestParam("ids") List<UUID> ids) {
        return ResponseEntity.ok(ApiResponse.success(visitService.summaries(ids)));
    }
}
