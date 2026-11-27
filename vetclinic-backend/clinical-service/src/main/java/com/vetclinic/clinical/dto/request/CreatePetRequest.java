package com.vetclinic.clinical.dto.request;

import com.vetclinic.clinical.constant.PetGender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/** Chủ nuôi tự thêm: ownerId bị bỏ qua. Lễ tân thêm hộ: bắt buộc ownerId. */
public record CreatePetRequest(
        UUID ownerId,
        @NotNull(message = "Loài không được để trống") Short speciesId,
        @NotBlank(message = "Tên thú cưng không được để trống") @Size(max = 100, message = "Tên tối đa 100 ký tự") String name,
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
