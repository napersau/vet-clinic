package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.dto.request.PrescriptionRequest;
import com.vetclinic.clinical.dto.response.PrescriptionResponse;
import com.vetclinic.clinical.service.PrescriptionService;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Đơn thuốc điện tử của một đợt khám (UC-31, UC-39), thuộc ngữ cảnh lâm sàng. Hóa đơn thuốc do billing tạo NHÁP khi Bác sĩ chốt ca; đơn thuốc
 * không có API lập hóa đơn.
 */
@RestController
@RequestMapping("/api/v1/clinical/pet-visits/{visitId}/prescription")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    /** UC-31: lưu CẢ đơn (tạo lần đầu hoặc thay nội dung). Chỉ Bác sĩ phụ trách khi ca IN_PROGRESS; kê vượt tồn / trùng dị ứng chỉ cảnh báo. */
    @PutMapping
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> save(@PathVariable UUID visitId, @Valid @RequestBody PrescriptionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã lưu đơn thuốc", prescriptionService.save(visitId, request)));
    }

    /** UC-39: chủ nuôi chỉ xem được đơn của thú cưng mình. */
    @GetMapping
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> get(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(prescriptionService.getByVisit(visitId)));
    }

    /** In đơn cho khách mua ngoài (BR-32): không tạo hóa đơn, không đổi gì trong hệ thống. */
    @GetMapping("/print")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PrescriptionResponse>> print(@PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(prescriptionService.print(visitId)));
    }
}
