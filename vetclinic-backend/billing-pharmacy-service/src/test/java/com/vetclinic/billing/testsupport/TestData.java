package com.vetclinic.billing.testsupport;

import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineCategory;
import com.vetclinic.billing.entity.MedicinePrice;
import com.vetclinic.billing.entity.MedicineStock;
import com.vetclinic.billing.service.MedicinePriceBook;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class TestData {

    /** 2026-10-30 09:00 giờ Việt Nam. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    public static final UUID OWNER = uid(5);
    public static final UUID RECEPTIONIST = uid(9);
    public static final UUID DOCTOR = uid(21);
    public static final UUID OTHER_DOCTOR = uid(22);

    private TestData() {
    }

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    public static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    /** UUID cố định của thuốc thử (019a9442-d808-7000-8000-0000000000NN), cùng dạng với dữ liệu seed. */
    public static UUID mid(long n) {
        return UUID.fromString(String.format("019a9442-d808-7000-8000-%012d", n));
    }

    /** Giá bán hiện hành của từng thuốc thử, để {@link #priceBook()} trả đúng giá như {@code medicine_prices} (VAT 5%). */
    private static final java.util.Map<UUID, BigDecimal> PRICES = new java.util.concurrent.ConcurrentHashMap<>();

    public static MedicineCategory category() {
        return MedicineCategory.builder().id((short) 1).code("ANTIBIOTIC").name("Kháng sinh").build();
    }

    /** Thuốc thử: giá không nằm trên thuốc mà đăng ký vào {@link #priceBook()}; tồn kho là dòng medicine_stocks gắn kèm. */
    public static Medicine medicine(long id, String name, String price, int stock) {
        Medicine medicine = Medicine.builder().id(mid(id)).category(category()).code("MED_" + id).name(name).dosageForm("Viên nén")
                .baseUnit("Viên").packagingSpec("Hộp 10 vỉ x 10 viên").minStockAlert(10).active(true).build();
        medicine.setStock(MedicineStock.builder().medicineId(medicine.getId()).medicine(medicine).quantityOnHand(stock).version(0L).build());
        PRICES.put(medicine.getId(), new BigDecimal(price));
        return medicine;
    }

    /** Đổi tồn kho của thuốc thử (mô phỏng UPDATE nguyên tử + refresh). */
    public static void setStock(Medicine medicine, int quantity) {
        medicine.getStock().setQuantityOnHand(quantity);
    }

    /** MedicinePriceBook giả: giá hiện hành = giá đã khai báo khi tạo thuốc thử, VAT 5%. Thuốc không đăng ký -> PHARM_1412. */
    public static MedicinePriceBook priceBook() {
        MedicinePriceBook book = org.mockito.Mockito.mock(MedicinePriceBook.class);
        org.mockito.Mockito.lenient().when(book.current(org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(inv -> {
            UUID medicineId = inv.getArgument(0);
            BigDecimal price = PRICES.get(medicineId);
            if (price == null) {
                throw new AppException(ErrorCode.MEDICINE_PRICE_NOT_FOUND);
            }
            return MedicinePrice.builder().salePrice(price).vatRate(new BigDecimal("0.05")).build();
        });
        return book;
    }

    /** UUID cố định của đợt khám thử (019a9442-d840-7000-8000-0000000000NN), cùng dạng với bên clinical. */
    public static UUID vid(long n) {
        return UUID.fromString(String.format("019a9442-d840-7000-8000-%012d", n));
    }

    /** UUID cố định của đơn thuốc thử (019a9442-d850-7000-8000-0000000000NN). */
    public static UUID pid(long n) {
        return UUID.fromString(String.format("019a9442-d850-7000-8000-%012d", n));
    }

    public static void loginAs(UUID userId, RoleCode role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    public static void assertError(ErrorCode expected, ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(expected);
    }
}
