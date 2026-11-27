package com.vetclinic.clinical.controller;

import java.util.UUID;
import com.vetclinic.clinical.dto.request.CancelServiceRequest;
import com.vetclinic.clinical.dto.response.LabResultResponse;
import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.dto.request.LabResultRequest;
import com.vetclinic.clinical.dto.request.OptionalReasonRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.response.QueueItemResponse;
import com.vetclinic.clinical.dto.response.ServiceDetailResponse;
import com.vetclinic.clinical.service.ParaclinicService;
import com.vetclinic.clinical.service.FileUrlSigner;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Hai hàng đợi cận lâm sàng độc lập (Lab, CĐHA) và thao tác trên từng chỉ định. */
@RestController
@RequestMapping("/api/v1/clinical")
@RequiredArgsConstructor
public class ParaclinicController {

    private final ParaclinicService paraclinicService;

    @GetMapping("/lab-queue")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<QueueItemResponse>>> labQueue() {
        return ResponseEntity.ok(ApiResponse.success(paraclinicService.queue(QueueType.LABORATORY)));
    }

    @GetMapping("/imaging-queue")
    @PreAuthorize("hasAnyRole('IMAGING_TECHNICIAN','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<QueueItemResponse>>> imagingQueue() {
        return ResponseEntity.ok(ApiResponse.success(paraclinicService.queue(QueueType.IMAGING)));
    }

    /** Phòng Lab: đã lấy mẫu xong (huy hiệu liên phòng cho CĐHA). */
    @PostMapping("/service-details/{id}/sample-collected")
    @PreAuthorize("hasRole('LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<ServiceDetailResponse>> sampleCollected(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã ghi nhận lấy mẫu", paraclinicService.sampleCollected(id)));
    }

    /** FR-30: Bác sĩ ghi nhận dịch vụ tại phòng "Đã thực hiện" (tiêm phòng, tẩy giun, thủ thuật...). */
    @PostMapping("/service-details/{id}/perform")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<ServiceDetailResponse>> perform(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã ghi nhận thực hiện dịch vụ", paraclinicService.perform(id)));
    }

    /**
     * EX-08: hủy chỉ định. reasonType CUSTOMER (khách tự hủy: hóa đơn đã thu giữ PAID) / CLINIC (phòng khám không làm được:
     * hóa đơn đã thu -> REFUNDED). Phí khám không hoàn (CLIN_1310).
     */
    @PostMapping("/service-details/{id}/cancel")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN','IMAGING_TECHNICIAN','VETERINARIAN')")
    public ResponseEntity<ApiResponse<ServiceDetailResponse>> cancel(@PathVariable UUID id,
                                                                     @Valid @RequestBody CancelServiceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã hủy chỉ định",
                paraclinicService.cancel(id, request.reason(), request.reasonType())));
    }

    /** Form nhập kết quả: chỉ các chỉ số của dịch vụ, kèm khoảng tham chiếu của loài thú cưng. */
    @GetMapping("/service-details/{id}/lab-form")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN','VETERINARIAN')")
    public ResponseEntity<ApiResponse<List<LabTestItemResponse>>> labForm(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(paraclinicService.labForm(id)));
    }

    /** Kết quả xét nghiệm đã nhập của một chỉ định (có nhãn LOW / NORMAL / HIGH). */
    @GetMapping("/lab-results/{id}")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<LabResultResponse>>> getLabResults(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(paraclinicService.labResults(id)));
    }

    /**
     * UC-25: nhập chỉ số xét nghiệm; hệ thống tự đối chiếu khoảng tham chiếu của loài. Body {@code {"results":[{"labTestItemId","resultValue"}]}}.
     * Đường dẫn cũ {@code /service-details/{id}/lab-results} còn giữ tạm cho tới khi FE đổi (Phase 6).
     */
    @PostMapping({"/lab-results/{id}", "/service-details/{id}/lab-results"})
    @PreAuthorize("hasRole('LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<ServiceDetailResponse>> labResults(@PathVariable UUID id,
                                                                         @Valid @RequestBody LabResultRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã lưu kết quả xét nghiệm", paraclinicService.submitLabResults(id, request)));
    }

    /** UC-26: multipart/form-data gồm description, conclusion và một hoặc nhiều tệp ảnh trong phần "files". */
    @PostMapping(path = {"/imaging-results/{id}", "/service-details/{id}/imaging-result"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('IMAGING_TECHNICIAN')")
    public ResponseEntity<ApiResponse<ServiceDetailResponse>> imagingResult(
            @PathVariable UUID id,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String conclusion,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return ResponseEntity.ok(ApiResponse.success("Đã lưu kết quả chẩn đoán hình ảnh",
                paraclinicService.submitImagingResult(id, description, conclusion, files)));
    }

    /** Xin URL ký tạm (1 giờ) của một tệp ảnh CĐHA; ảnh được tải ở GET /files/** chỉ bằng chữ ký (Quy chuẩn 7.5). */
    @GetMapping("/imaging-attachments/{id}/url")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','IMAGING_TECHNICIAN','ADMIN')")
    public ResponseEntity<ApiResponse<FileUrlSigner.SignedUrl>> attachmentUrl(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(paraclinicService.attachmentUrl(id)));
    }
}
