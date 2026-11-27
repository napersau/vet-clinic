package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.dto.request.CreatePetRequest;
import com.vetclinic.clinical.dto.request.UpdatePetRequest;
import com.vetclinic.clinical.dto.response.MedicalHistoryEntry;
import com.vetclinic.clinical.dto.response.PetResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.service.MedicalHistoryService;
import com.vetclinic.clinical.service.PetService;
import com.vetclinic.clinical.service.VisitEnricher;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

/** Hồ sơ thú cưng (UC-12, UC-13, UC-18) và dòng thời gian bệnh án (UC-38). */
@RestController
@RequestMapping("/api/v1/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;
    private final MedicalHistoryService medicalHistoryService;
    private final VisitEnricher enricher;

    /** Chủ nuôi tự thêm (ownerId bị bỏ qua) hoặc Lễ tân thêm hộ (bắt buộc ownerId). */
    @PostMapping
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST')")
    public ResponseEntity<ApiResponse<PetResponse>> create(@Valid @RequestBody CreatePetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Thêm hồ sơ thú cưng thành công", petService.create(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<PetResponse>>> search(
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) String ownerPhone,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(petService.search(ownerId, ownerPhone, keyword, page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','LAB_TECHNICIAN','IMAGING_TECHNICIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PetResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(petService.getById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST')")
    public ResponseEntity<ApiResponse<PetResponse>> update(@PathVariable UUID id,
                                                           @Valid @RequestBody UpdatePetRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật hồ sơ thành công", petService.update(id, request)));
    }

    /** Xóa mềm hồ sơ thú cưng (không xóa được khi đang có đợt khám mở); bệnh án cũ vẫn được giữ. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        petService.delete(id);
        return ResponseEntity.ok(ApiResponse.<Void>success("Đã xóa hồ sơ thú cưng", null));
    }

    /**
     * UC-38 / UC-39: bệnh án điện tử trọn đời dạng timeline (chẩn đoán, dịch vụ, kết quả xét nghiệm, ảnh CĐHA, đơn thuốc và
     * hướng dẫn dùng thuốc), phân trang, mới nhất trước. Chủ nuôi chỉ xem được các đợt đã hoàn tất của thú cưng mình.
     */
    @GetMapping("/{id}/medical-history")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<MedicalHistoryEntry>>> medicalHistory(
            @PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        var history = medicalHistoryService.history(id, page, size);
        // EX-17: Bác sĩ đã khóa vẫn hiện tên (snapshot) kèm nhãn "Đã khóa": tra trạng thái một lần theo lô, ngoài transaction
        var visits = enricher.enrichAll(history.getItems().stream().map(MedicalHistoryEntry::visit).toList());
        var entries = history.getItems();
        history.setItems(java.util.stream.IntStream.range(0, entries.size())
                .mapToObj(i -> new MedicalHistoryEntry(visits.get(i), entries.get(i).doctorName(), entries.get(i).prescription())).toList());
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    /** Danh sách gọn các đợt khám của thú cưng, mới nhất trước. */
    @GetMapping("/{id}/visits")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<VisitResponse>>> visits(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(petService.getVisits(id)));
    }
}
