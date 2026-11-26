package com.vetclinic.common.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Sinh UUID phiên bản 7 (RFC 9562): 48 bit đầu là epoch mili giây nên giá trị sinh sau luôn lớn hơn (chèn vào cuối chỉ mục
 * B+Tree như số tự tăng), phần còn lại ngẫu nhiên. Dùng cho {@code eventId} của sự kiện / outbox; khóa chính entity dùng
 * {@code @UuidGenerator(style = VERSION_7)} của Hibernate (ERD v4.0 mục 1.2).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    public static UUID generate(Clock clock) {
        return generate(clock.millis());
    }

    static UUID generate(long epochMillis) {
        long randA = RANDOM.nextInt(1 << 12);                       // 12 bit
        long msb = ((epochMillis & 0xFFFF_FFFF_FFFFL) << 16)         // 48 bit thời gian
                | (0x7L << 12)                                       // phiên bản 7
                | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)      // 62 bit ngẫu nhiên
                | 0x8000_0000_0000_0000L;                            // biến thể RFC (10xx)
        return new UUID(msb, lsb);
    }

    /** Thời điểm (epoch ms) nằm trong một UUIDv7. */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Không phải UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
