package com.vetclinic.billing.controller;

import com.vetclinic.billing.dto.response.MedicineResponse;
import com.vetclinic.billing.service.MedicineService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tra cứu thuốc cho service khác gọi qua OpenFeign (clinical-service kê đơn từ Phase 5.2: tên, đơn vị, giá hiện hành, tồn kho, cờ đang kinh doanh).
 * Dưới /internal/**: không cần header người dùng, Gateway không định tuyến ra ngoài. Không có người dùng nên chỉ trả thuốc ĐANG kinh doanh.
 */
@RestController
@RequestMapping("/internal/medicines")
@RequiredArgsConstructor
public class InternalMedicineController {

    private final MedicineService medicineService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MedicineResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.search(keyword, categoryCode, Boolean.TRUE, false, page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MedicineResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(medicineService.getById(id)));
    }
}
