package com.vetclinic.clinical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Kho tệp (Quy chuẩn 7.5): CSDL chỉ lưu {@code file_key} (đường dẫn tương đối dưới {@code root}); URL tuyệt đối không bao giờ vào CSDL,
 * đổi máy chủ / tên miền / kho đối tượng chỉ đổi cấu hình.
 *
 * @param root                thư mục gốc của kho tệp (Docker volume /app/uploads); file_key dạng imaging/2026/11/&lt;uuid&gt;.jpg nằm dưới đây
 * @param secret              khóa HMAC-SHA256 ký URL tạm (tối thiểu 16 byte)
 * @param urlTtl              thời hạn của URL ký (mặc định 1 giờ)
 * @param allowedContentTypes các loại tệp được phép tải lên
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("./uploads") String root,
        String secret,
        @DefaultValue("1h") Duration urlTtl,
        @DefaultValue({"image/jpeg", "image/png", "application/dicom"}) List<String> allowedContentTypes) {
}
