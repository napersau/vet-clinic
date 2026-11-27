package com.vetclinic.clinical.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Body của docs v3.0: {@code {"results":[{"labTestItemId":1,"resultValue":9.5}]}}; vẫn nhận tên cũ {@code items} / {@code value}. */
public record LabResultRequest(
        @JsonAlias("items") @NotEmpty(message = "Phải nhập ít nhất một chỉ số") @Size(max = 50, message = "Tối đa 50 chỉ số mỗi lần") List<@Valid LabResultItemRequest> results
) {
}
