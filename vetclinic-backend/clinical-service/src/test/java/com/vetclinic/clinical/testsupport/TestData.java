package com.vetclinic.clinical.testsupport;

import com.vetclinic.clinical.constant.PetGender;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.ServicePrice;
import com.vetclinic.clinical.entity.Species;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.service.ServicePriceBook;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import java.time.LocalDate;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class TestData {

    /** 2026-10-30 09:00 giờ Việt Nam. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    public static final UUID OWNER = uid(5);
    public static final UUID RECEPTIONIST = uid(9);
    public static final UUID DOCTOR = uid(21);
    public static final UUID OTHER_DOCTOR = uid(22);
    public static final UUID LAB_TECH = uid(31);
    public static final UUID IMAGING_TECH = uid(41);
    public static final UUID ADMIN_ID_FOR_PRICES = uid(1);

    private TestData() {
    }

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    public static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    public static Species dog() {
        return Species.builder().id((short) 1).code("DOG").name("Chó").build();
    }

    public static Species cat() {
        return Species.builder().id((short) 2).code("CAT").name("Mèo").build();
    }

    /** UUID cố định của thú cưng thử (dạng seed 019a9442-d820-7000-8000-0000000000NN). */
    public static UUID pid(long n) {
        return UUID.fromString(String.format("019a9442-d820-7000-8000-%012d", n));
    }

    /** UUID cố định của lịch hẹn thử (019a9442-d830-7000-8000-0000000000NN). */
    public static UUID apid(long n) {
        return UUID.fromString(String.format("019a9442-d830-7000-8000-%012d", n));
    }

    public static Pet pet(UUID id, Species species) {
        return Pet.builder().id(id).ownerId(OWNER).ownerNameSnapshot("Hoàng Thị Lan").ownerPhoneSnapshot("0912000008")
                .species(species).petCode("PET-2026-" + id.toString().substring(31)).name("Lu").gender(PetGender.MALE).build();
    }

    /**
     * UUID cố định cho đợt khám / dịch vụ chỉ định / hóa đơn dịch vụ thử (019a9442-d840-7000-8000-0000000000NN): test vẫn đánh số
     * ngắn (10L, 100L...) nhưng khóa thật là UUID. Cùng một ánh xạ cho mọi bảng nên tránh dùng cùng số cho hai bảng trong một test.
     */
    public static UUID lid(long n) {
        return UUID.fromString(String.format("019a9442-d840-7000-8000-%012d", n));
    }

    public static PetVisit visit(long id, VisitStatus status, UUID doctorId) {
        return PetVisit.builder().id(lid(id)).visitCode("VIS-2026-" + id).pet(pet(pid(11), dog())).doctorId(doctorId)
                .receptionistId(RECEPTIONIST).status(status)
                .checkedInAt(Instant.parse("2026-10-30T01:00:00Z"))
                .build();
    }

    /** UUID cố định của dịch vụ thử (dạng seed 019a9442-d801-7000-8000-0000000000NN). */
    public static UUID sid(long n) {
        return UUID.fromString(String.format("019a9442-d801-7000-8000-%012d", n));
    }

    /** Đơn giá của từng dịch vụ thử, để {@link #priceBook()} trả đúng giá như {@code service_prices}. */
    private static final java.util.Map<UUID, BigDecimal> PRICES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Nhóm dịch vụ giống seed của docs: queue_type và chính sách thu trước do nhóm quyết định. */
    public static ServiceCategory category(String code) {
        return switch (code) {
            case "EXAMINATION" -> ServiceCategory.builder().id((short) 1).code(code).name("Khám bệnh").queueType("DOCTOR").requiresPrepayment(true).build();
            case "LABORATORY" -> ServiceCategory.builder().id((short) 6).code(code).name("Xét nghiệm").queueType("LABORATORY").requiresPrepayment(true).build();
            case "IMAGING" -> ServiceCategory.builder().id((short) 7).code(code).name("Chẩn đoán hình ảnh").queueType("IMAGING").requiresPrepayment(true).build();
            default -> ServiceCategory.builder().id((short) 2).code(code).name(code).requiresPrepayment(false).build();   // tại phòng: queue_type NULL
        };
    }

    public static ClinicService service(long id, String code, String categoryCode, String price) {
        ClinicService service = ClinicService.builder().id(sid(id)).code(code).name("Dịch vụ " + code)
                .category(category(categoryCode)).active(true).build();
        PRICES.put(service.getId(), new BigDecimal(price));
        return service;
    }

    /** ServicePriceBook giả: giá hiện hành = giá đã khai báo khi tạo dịch vụ thử, VAT 0. */
    public static ServicePriceBook priceBook() {
        ServicePriceBook book = org.mockito.Mockito.mock(ServicePriceBook.class);
        org.mockito.Mockito.lenient().when(book.current(org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(inv -> {
            UUID serviceId = inv.getArgument(0);
            return ServicePrice.builder().price(PRICES.get(serviceId)).vatRate(new BigDecimal("0.00")).build();
        });
        return book;
    }

    public static VisitServiceDetail detail(long id, long visitId, ClinicService service, ServiceDetailStatus status) {
        Instant createdAt = Instant.parse("2026-10-30T01:30:00Z").plusSeconds(id);
        // Giá / VAT không còn ở dòng chỉ định (v3.0) mà ở hóa đơn dịch vụ
        return VisitServiceDetail.builder().id(lid(id)).visitId(lid(visitId)).service(service)
                .quantity(1).status(status).createdAt(createdAt).build();
    }

    /** UUID cố định của phiếu xếp hàng thử (019a9442-d850-7000-8000-0000000000NN). */
    public static UUID tid(long n) {
        return UUID.fromString(String.format("019a9442-d850-7000-8000-%012d", n));
    }

    /** Phiếu xếp hàng (đọc từ Redis) phát lúc 09:00 + id giây (thứ tự phát = thứ tự id). */
    public static TicketView ticket(long id, QueueType type, long visitId, UUID doctorId, int ticketNo, int priority, TicketStatus status) {
        return ticket(id, type, visitId, doctorId, ticketNo, priority, status, List.of());
    }

    public static TicketView ticket(long id, QueueType type, long visitId, UUID doctorId, int ticketNo, int priority, TicketStatus status,
                                    List<UUID> vsdIds) {
        return new TicketView(tid(id), type, LocalDate.of(2026, 10, 30), ticketNo, lid(visitId), doctorId, priority, status,
                Instant.parse("2026-10-30T02:00:00Z").plusSeconds(id), null, 0, null, null, vsdIds, "Lu", "Hoàng Thị Lan", null);
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
