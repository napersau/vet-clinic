package com.vetclinic.billing.controller;

import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.service.MedicineInvoiceService;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Hóa đơn thuốc (UC-36). KHÔNG có API lập hóa đơn: nháp tự có khi Bác sĩ chốt ca (PrescriptionCreatedEvent). Chuyển trạng thái dùng
 * POST /{id}/action; sửa dòng thuốc là PUT (sửa dữ liệu).
 */
@RestController
@RequestMapping("/api/v1/billing/medicine-invoices")
@RequiredArgsConstructor
public class MedicineInvoiceController {

    private final MedicineInvoiceService invoiceService;

    /** Màn hình Thu ngân: hóa đơn thuốc (nháp hoặc đã xử lý) của một đợt khám. */
    @GetMapping
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<List<MedicineInvoiceResponse>>> byVisit(@RequestParam UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.findByVisit(visitId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.getById(id)));
    }

    /** Sửa các dòng thuốc / giảm giá khi hóa đơn còn PENDING (quantity = 0 là bỏ dòng). */
    @PutMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> updateItems(@PathVariable UUID id, @Valid @RequestBody MedicineInvoiceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật hóa đơn thuốc", invoiceService.updateItems(id, request)));
    }

    /** Khách mua ngoài: hủy hóa đơn PENDING (kho không đổi). Hủy là kết thúc, đơn không có hóa đơn thuốc khác. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã hủy hóa đơn thuốc", invoiceService.cancel(id)));
    }

    /** Thu tiền + trừ kho + ghi nhật ký kho trong một giao dịch; thiếu kho thì rollback toàn bộ (EX-15). */
    @PostMapping("/{id}/confirm-payment")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<MedicineInvoiceResponse>> confirmPayment(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã thu tiền và trừ kho", invoiceService.confirmPayment(id)));
    }
}
