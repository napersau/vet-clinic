package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Một lần đo sinh hiệu (docs v4.0 mục 23). Mọi chỉ số đều tùy chọn nhưng phải có ít nhất một chỉ số (kiểm tra ở service);
 * muốn đi tiếp sang cận lâm sàng / chốt ca thì phải có ít nhất một lần đo đủ cả cân nặng và thân nhiệt (CLIN_1308).
 * Kiểu bao (Integer, BigDecimal) vì Jackson 3 từ chối thiếu trường kiểu nguyên thủy.
 */
public record VitalSignsRequest(
        @DecimalMin(value = "0.1", message = "Cân nặng phải lớn hơn 0.1 kg") @DecimalMax(value = "150.0", message = "Cân nặng không hợp lý (tối đa 150 kg)") BigDecimal weightKg,
        @DecimalMin(value = "30.0", message = "Thân nhiệt đo được quá thấp (tối thiểu 30°C)") @DecimalMax(value = "45.0", message = "Thân nhiệt đo được quá cao (tối đa 45°C)") BigDecimal temperatureC,
        @Min(value = 10, message = "Nhịp tim tối thiểu 10 lần/phút") @Max(value = 400, message = "Nhịp tim tối đa 400 lần/phút") Integer heartRateBpm,
        @Min(value = 5, message = "Nhịp thở tối thiểu 5 lần/phút") @Max(value = 200, message = "Nhịp thở tối đa 200 lần/phút") Integer respiratoryRateBpm,
        @Min(value = 1, message = "Điểm thể trạng (BCS) từ 1 đến 9") @Max(value = 9, message = "Điểm thể trạng (BCS) từ 1 đến 9") Integer bodyConditionScore,
        @Size(max = 30, message = "Màu niêm mạc tối đa 30 ký tự") String mucousMembraneColor,
        @DecimalMin(value = "0.0", message = "Thời gian làm đầy mao mạch không âm") @DecimalMax(value = "10.0", message = "Thời gian làm đầy mao mạch tối đa 10 giây") BigDecimal capillaryRefillSec,
        @Size(max = 255, message = "Ghi chú tối đa 255 ký tự") String notes
) {

    /** Không có chỉ số nào và không có ghi chú (một lần đo trống thì không ghi). */
    public boolean isEmpty() {
        return weightKg == null && temperatureC == null && heartRateBpm == null && respiratoryRateBpm == null
                && bodyConditionScore == null && (mucousMembraneColor == null || mucousMembraneColor.isBlank())
                && capillaryRefillSec == null && (notes == null || notes.isBlank());
    }
}
