package com.vetclinic.reporting.controller;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.reporting.dto.response.RebuildResponse;
import com.vetclinic.reporting.service.ReportRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Công cụ quản trị chạy tay (Task 4.5.4): dựng lại báo cáo từ outbox của các service nguồn. Chỉ ADMIN. */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ReportAdminController {

    private final ReportRebuildService rebuildService;

    /**
     * Dựng lại báo cáo của {@code days} ngày nghiệp vụ gần nhất (mặc định 3, tối đa 6) từ {@code outbox_events}; các ngày cũ hơn giữ nguyên.
     * Idempotent: chạy lại cho cùng kết quả.
     */
    @PostMapping("/rebuild")
    public ResponseEntity<ApiResponse<RebuildResponse>> rebuild(@RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(ApiResponse.success("Đã dựng lại báo cáo", rebuildService.rebuild(days)));
    }
}
