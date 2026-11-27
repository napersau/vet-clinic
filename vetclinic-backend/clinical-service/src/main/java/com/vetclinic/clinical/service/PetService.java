package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.request.CreatePetRequest;
import com.vetclinic.clinical.dto.request.UpdatePetRequest;
import com.vetclinic.clinical.dto.response.PetResponse;
import com.vetclinic.clinical.dto.response.PetSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.common.dto.PageResponse;

import java.util.List;
import java.util.UUID;

public interface PetService {

    /** UC-12 / UC-18: chủ nuôi tự thêm, hoặc lễ tân thêm hộ. Chụp tên + SĐT chủ nuôi (Feign auth) vào snapshot. */
    PetResponse create(CreatePetRequest request);

    /** Sửa hồ sơ. Đổi tên hoặc chuyển chủ thì ghi {@code PetUpdatedEvent} vào outbox cùng giao dịch. */
    PetResponse update(UUID id, UpdatePetRequest request);

    /** Xóa mềm ({@code deleted_at}); không xóa được khi thú cưng đang có đợt khám mở. */
    void delete(UUID id);

    PetResponse getById(UUID id);

    /**
     * Chủ nuôi chỉ thấy thú cưng của mình. Lọc theo chủ nuôi, SĐT chủ (dùng snapshot, không gọi auth-service) và từ khóa (tên, mã hồ sơ,
     * số vi mạch). Hồ sơ đã xóa mềm bị ẩn.
     */
    PageResponse<PetResponse> search(UUID ownerId, String ownerPhone, String keyword, int page, int size);

    /** UC-38: dòng thời gian các đợt khám của thú cưng, mới nhất trước. */
    List<VisitResponse> getVisits(UUID petId);

    /** Cho appointment-service (Feign nội bộ): thú cưng tồn tại, chưa xóa, kèm chủ nuôi và tên để chụp snapshot lịch hẹn. */
    PetSummaryResponse getSummary(UUID id);

    /** Consumer {@code UserProfileUpdatedEvent}: cập nhật snapshot chủ nuôi của mọi thú cưng thuộc {@code ownerId}. Trả số hồ sơ đã cập nhật. */
    int refreshOwnerSnapshot(UUID ownerId, String ownerName, String ownerPhone);
}
