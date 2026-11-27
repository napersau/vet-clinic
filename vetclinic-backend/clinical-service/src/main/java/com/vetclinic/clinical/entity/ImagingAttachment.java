package com.vetclinic.clinical.entity;

import java.util.UUID;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

@Entity
@Table(name = "imaging_attachments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingAttachment {
    /** UUIDv7: không đoán được khi xin URL tải ảnh. */
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "imaging_result_id", nullable = false)
    private UUID imagingResultId;

    /** Đường dẫn tương đối trong kho tệp: imaging/yyyy/MM/&lt;uuidv7&gt;.&lt;đuôi&gt;. Không lưu URL tuyệt đối (Quy chuẩn 7.5). */
    @Column(name = "file_key", nullable = false, unique = true)
    private String fileKey;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /** Khóa ngoại logic -> auth.users(id): kỹ thuật viên CĐHA tải lên. */
    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;
}
