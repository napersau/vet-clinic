package com.vetclinic.common.util;

import java.security.SecureRandom;

/**
 * Sinh mã hiển thị ngắn (APT-20261121-K3F9QX, PET-2026-7HN2KD, ...) cho thực thể có khóa UUID. Khóa chính không còn là số tăng dần nên mã
 * không thể dựa vào id; phần đuôi ngẫu nhiên 6 ký tự (bảng chữ cái bỏ ký tự dễ nhầm O/0, I/1) cho ~887 triệu khả năng mỗi tiền tố.
 * Tính duy nhất vẫn do ràng buộc UNIQUE của CSDL bảo đảm; nơi gọi kiểm tra trùng rồi thử lại.
 */
public final class Codes {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {
    }

    /** Chuỗi ngẫu nhiên gồm {@code length} ký tự thuộc bảng chữ cái trên. */
    public static String randomSuffix(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
