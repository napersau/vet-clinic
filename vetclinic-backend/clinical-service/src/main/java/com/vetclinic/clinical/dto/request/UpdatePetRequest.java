package com.vetclinic.clinical.dto.request;

import com.vetclinic.clinical.constant.PetGender;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Trường nào null thì giữ nguyên. Không đổi được loài (loài quyết định khoảng tham chiếu xét nghiệm). {@code ownerId}: chỉ Lễ tân được
 * chuyển hồ sơ sang chủ khác (chủ mới phải là tài khoản PET_OWNER đang hoạt động). {@code microchipNumber} / {@code allergies} chuỗi rỗng = xóa.
 */
public record UpdatePetRequest(
        @Size(min = 1, max = 100, message = "Tên từ 1 đến 100 ký tự") String name,
        UUID ownerId,
        @Size(max = 100, message = "Giống tối đa 100 ký tự") String breed,
        PetGender gender,
        @PastOrPresent(message = "Ngày sinh không được ở tương lai") LocalDate dateOfBirth,
        @Size(max = 100, message = "Màu lông tối đa 100 ký tự") String color,
        Boolean neutered,
        @Size(max = 30, message = "Số vi mạch tối đa 30 ký tự") String microchipNumber,
        @Size(max = 1000, message = "Đặc điểm nhận dạng tối đa 1000 ký tự") String identifyingCharacteristics,
        @Size(max = 1000, message = "Tiền sử dị ứng tối đa 1000 ký tự") String allergies,
        @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự") String notes
) {
}
