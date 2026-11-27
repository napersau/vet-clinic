package com.vetclinic.clinical.dto.response;

import java.util.UUID;

/** Tệp đính kèm của kết quả CĐHA. Không có URL: xin URL ký tạm bằng GET /imaging-attachments/{id}/url (Quy chuẩn 7.5). */
public record AttachmentResponse(
        UUID id,
        String fileName,
        Long fileSize,
        String contentType
) {
}
