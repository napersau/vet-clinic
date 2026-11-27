package com.vetclinic.clinical.controller;

import java.util.UUID;
import com.vetclinic.clinical.dto.request.ConfirmPaymentRequest;
import com.vetclinic.clinical.dto.request.DiscountRequest;
import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.service.ServiceInvoiceService;
import com.vetclinic.clinical.service.VisitEnricher;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Hóa đơn dịch vụ: bảng kê, giảm giá từng hóa đơn, thu tiền (T10) và công nợ. Thu ngân = Lễ tân. */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class ServiceInvoiceController {

    private final ServiceInvoiceService invoiceService;
    private final VisitEnricher enricher;

    @GetMapping("/pet-visits/{id}/service-invoices")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceStatementResponse>> statement(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(invoiceService.statement(id)));
    }

    @PostMapping("/service-invoices/{id}/discount")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<ServiceInvoiceResponse>> discount(@PathVariable UUID id,
                                                                        @Valid @RequestBody DiscountRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã áp dụng giảm giá", invoiceService.discount(id, request.discountAmount())));
    }

    @PostMapping("/pet-visits/{id}/service-invoices/confirm-payment")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<InvoiceStatementResponse>> confirmPayment(@PathVariable UUID id,
                                                                                @Valid @RequestBody ConfirmPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã xác nhận thanh toán", invoiceService.confirmPayment(id, request.invoiceIds())));
    }

    /**
     * Màn hình Thu ngân (BR-35): EXAM_FEE = ca vừa tiếp đón chờ nộp phí khám (chưa có phiếu); PARACLINIC = ca chờ nộp tiền
     * Xét nghiệm / CĐHA; FINAL = ca đã chốt chờ thu các dịch vụ còn lại (WAITING_PAYMENT).
     */
    @GetMapping("/cashier/pending")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<List<VisitResponse>>> cashierPending(
            @RequestParam ServiceInvoiceService.CashierSection section) {
        return ResponseEntity.ok(ApiResponse.success(enricher.enrichAll(invoiceService.cashierPending(section))));
    }

    /** Ca COMPLETED còn hóa đơn PENDING (khách bỏ về, công nợ). */
    @GetMapping("/pet-visits/with-debt")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<List<VisitResponse>>> withDebt() {
        return ResponseEntity.ok(ApiResponse.success(enricher.enrichAll(invoiceService.visitsWithDebt())));
    }
}
