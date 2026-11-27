package com.vetclinic.reporting.controller;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.reporting.dto.response.DoctorPerformanceResponse;
import com.vetclinic.reporting.dto.response.RevenueByCategoryResponse;
import com.vetclinic.reporting.dto.response.RevenueDailyResponse;
import com.vetclinic.reporting.dto.response.VisitSummaryResponse;
import com.vetclinic.reporting.service.ReportQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * API báo cáo (FR-69, FR-70): chỉ ĐỌC, chỉ {@code ADMIN}. Không có API ghi: dữ liệu vào bằng listener sự kiện RabbitMQ.
 * Ngày theo giờ phòng khám; khoảng mặc định 30 ngày gần nhất, tối đa 366 ngày.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportQueryService reports;

    /** Doanh thu từng ngày (đã trừ hoàn tiền), lọc theo {@code type=SERVICE|MEDICINE} hoặc cả hai. */
    @GetMapping("/revenue/daily")
    public ResponseEntity<ApiResponse<RevenueDailyResponse>> revenueDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type) {
        return ResponseEntity.ok(ApiResponse.success(reports.revenueDaily(from, to, type)));
    }

    /** Doanh thu theo nhóm dịch vụ / nhóm thuốc: {@code month=yyyy-MM} hoặc {@code from}/{@code to}. */
    @GetMapping("/revenue/by-category")
    public ResponseEntity<ApiResponse<RevenueByCategoryResponse>> revenueByCategory(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(reports.revenueByCategory(month, from, to)));
    }

    /** Hiệu suất từng Bác sĩ: ca hoàn tất / hủy / cấp cứu và doanh thu dịch vụ. */
    @GetMapping("/doctors/performance")
    public ResponseEntity<ApiResponse<DoctorPerformanceResponse>> doctorPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(reports.doctorPerformance(from, to)));
    }

    /** Tổng hợp ca khám (FR-70): {@code date=} (một ngày) hoặc {@code from}/{@code to}. */
    @GetMapping("/visits/summary")
    public ResponseEntity<ApiResponse<VisitSummaryResponse>> visitSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(reports.visitSummary(date, from, to)));
    }
}
