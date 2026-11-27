package com.vetclinic.clinical.service;

import com.vetclinic.clinical.config.StorageProperties;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** URL ký tạm của ảnh (Quy chuẩn 7.5): chữ ký HMAC theo file_key + hạn, hết hạn sau 1 giờ. */
class FileUrlSignerTest {

    private static final String KEY = "imaging/2026/10/019a9442-d840-7000-8000-000000000001.jpg";

    private static StorageProperties props(String secret) {
        return new StorageProperties("./uploads", secret, Duration.ofHours(1), List.of("image/jpeg"));
    }

    private final FileUrlSigner signer = new FileUrlSigner(props("test-secret-test-secret-0123456789"), CLOCK);

    private static long exp(String url) {
        return Long.parseLong(url.replaceAll(".*exp=(\\d+).*", "$1"));
    }

    private static String sig(String url) {
        return url.substring(url.indexOf("sig=") + 4);
    }

    @Test
    void signedUrl_hasTheFileKeyExpiryAndSignature_andVerifies() {
        FileUrlSigner.SignedUrl signed = signer.sign(KEY);

        assertThat(signed.url()).startsWith("/files/" + KEY + "?exp=").contains("&sig=");
        assertThat(signed.expiresAt()).isEqualTo(CLOCK.instant().plusSeconds(3600));
        assertThat(exp(signed.url())).isEqualTo(signed.expiresAt().getEpochSecond());
        signer.verify(KEY, exp(signed.url()), sig(signed.url()));   // hợp lệ: không ném ngoại lệ
    }

    @Test
    void tamperedKeyExpiryOrSignature_isRefused() {
        FileUrlSigner.SignedUrl signed = signer.sign(KEY);
        long exp = exp(signed.url());
        String sig = sig(signed.url());

        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY.replace(".jpg", ".png"), exp, sig));   // đổi file_key
        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY, exp + 1, sig));                        // kéo dài hạn
        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY, exp, "x" + sig.substring(1)));         // sửa chữ ký
        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY, exp, null));
        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY, null, sig));
        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY, exp, ""));
    }

    @Test
    void expiredUrl_isRefused_evenWithAValidSignature() {
        FileUrlSigner.SignedUrl signed = signer.sign(KEY);
        Clock later = Clock.fixed(CLOCK.instant().plus(Duration.ofHours(1)).plusSeconds(1), ZoneOffset.UTC);
        FileUrlSigner afterExpiry = new FileUrlSigner(props("test-secret-test-secret-0123456789"), later);

        assertError(ErrorCode.ACCESS_DENIED, () -> afterExpiry.verify(KEY, exp(signed.url()), sig(signed.url())));
        // đúng lúc hết hạn vẫn còn hiệu lực (chỉ từ chối khi đã QUA hạn)
        FileUrlSigner atExpiry = new FileUrlSigner(props("test-secret-test-secret-0123456789"), Clock.fixed(Instant.ofEpochSecond(exp(signed.url())), ZoneOffset.UTC));
        atExpiry.verify(KEY, exp(signed.url()), sig(signed.url()));
    }

    @Test
    void aSignatureMadeWithAnotherSecret_isRefused() {
        FileUrlSigner other = new FileUrlSigner(props("another-secret-another-secret-9876543210"), CLOCK);
        FileUrlSigner.SignedUrl foreign = other.sign(KEY);

        assertError(ErrorCode.ACCESS_DENIED, () -> signer.verify(KEY, exp(foreign.url()), sig(foreign.url())));
    }

    @Test
    void missingOrShortSecret_failsAtStartup() {
        assertThatThrownBy(() -> new FileUrlSigner(props(null), CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FileUrlSigner(props("ngan"), CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FileUrlSigner(props(""), CLOCK)).isInstanceOf(IllegalStateException.class);
    }
}
