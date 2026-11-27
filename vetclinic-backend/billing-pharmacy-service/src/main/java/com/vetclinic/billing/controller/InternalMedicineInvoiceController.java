package com.vetclinic.billing.controller;

import com.vetclinic.billing.dto.response.MedicineInvoiceStatusResponse;
import com.vetclinic.billing.service.MedicineInvoiceService;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cho clinical-service gọi qua OpenFeign (T14, mở lại hồ sơ): đơn thuốc đã có hóa đơn thuốc chưa, trạng thái gì; đã PAID thì không được sửa đơn
 * (PHARM_1411). Dưới /internal/**: không cần header người dùng, Gateway không định tuyến ra ngoài.
 */
@RestController
@RequestMapping("/internal/medicine-invoices")
@RequiredArgsConstructor
public class InternalMedicineInvoiceController {

    private final MedicineInvoiceService invoiceService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<MedicineInvoiceStatusResponse>> status(@RequestParam UUID prescriptionId) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.statusOfPrescription(prescriptionId)));
    }
}
