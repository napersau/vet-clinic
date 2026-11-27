package com.vetclinic.clinical.client;

import com.vetclinic.clinical.dto.response.MedicineInvoiceStatus;
import com.vetclinic.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/** Hóa đơn thuốc ở billing-pharmacy-service (chỉ ĐỌC, gọi NGOÀI transaction): đơn đã PAID thì Bác sĩ không sửa được đơn. */
@FeignClient(name = "billing-pharmacy-service", contextId = "medicineInvoiceClient", path = "/internal/medicine-invoices")
public interface MedicineInvoiceClient {

    @GetMapping("/status")
    ApiResponse<MedicineInvoiceStatus> status(@RequestParam("prescriptionId") UUID prescriptionId);
}
