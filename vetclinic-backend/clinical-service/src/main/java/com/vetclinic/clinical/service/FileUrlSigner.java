package com.vetclinic.clinical.service;

import com.vetclinic.clinical.config.StorageProperties;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * URL ký tạm cho tệp ảnh (Quy chuẩn 7.5): {@code /files/<file_key>?exp=<epoch giây>&sig=<HMAC-SHA256(file_key + exp)>}. Thẻ img của trình duyệt
 * không gắn được header Authorization nên quyền được kiểm tra MỘT LẦN lúc xin URL ({@code GET /imaging-attachments/{id}/url}); sau đó chữ ký
 * + hạn chứng minh URL do máy chủ cấp. Đổi một ký tự của file_key / exp, hoặc hết hạn, là bị từ chối.
 */
@Component
public class FileUrlSigner {

    public static final String PREFIX = "/files/";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 16;

    /** URL đã ký kèm thời điểm hết hạn để giao diện biết lúc nào phải xin lại. */
    public record SignedUrl(String url, Instant expiresAt) {
    }

    private final byte[] secret;
    private final StorageProperties properties;
    private final Clock clock;

    public FileUrlSigner(StorageProperties properties, Clock clock) {
        String configured = properties.secret();
        if (configured == null || configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("Thiếu khóa ký URL tệp: đặt app.storage.secret (STORAGE_SECRET hoặc JWT_SECRET), tối thiểu "
                    + MIN_SECRET_BYTES + " byte");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        this.properties = properties;
        this.clock = clock;
    }

    public SignedUrl sign(String fileKey) {
        Instant expiresAt = clock.instant().plus(properties.urlTtl());
        long exp = expiresAt.getEpochSecond();
        return new SignedUrl(PREFIX + fileKey + "?exp=" + exp + "&sig=" + signature(fileKey, exp), Instant.ofEpochSecond(exp));
    }

    /** @throws AppException ACCESS_DENIED nếu chữ ký sai hoặc đã hết hạn (không phân biệt để không lộ thông tin). */
    public void verify(String fileKey, Long exp, String sig) {
        if (exp == null || sig == null || sig.isBlank() || exp < clock.instant().getEpochSecond()) {
            throw new AppException(ErrorCode.ACCESS_DENIED, "Liên kết tệp không hợp lệ hoặc đã hết hạn");
        }
        byte[] expected = signature(fileKey, exp).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8))) {   // so sánh thời gian hằng số
            throw new AppException(ErrorCode.ACCESS_DENIED, "Liên kết tệp không hợp lệ hoặc đã hết hạn");
        }
    }

    private String signature(String fileKey, long exp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((fileKey + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được URL tệp", e);
        }
    }
}
