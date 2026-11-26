package com.vetclinic.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giữ đúng 49 mã lỗi của Quy chuẩn v4.0 mục 5.1 (tên, số mã, HTTP status). Mã chỉ có trong code thì không nằm ở đây
 * nhưng không được trùng số với mã nào của docs.
 */
class ErrorCodeCatalogTest {

    private record Spec(String enumName, HttpStatus status) {
    }

    private static final Map<String, Spec> DOCS_V4 = new LinkedHashMap<>();

    static {
        spec("SYS_1000", "UNCATEGORIZED_EXCEPTION", HttpStatus.INTERNAL_SERVER_ERROR);
        spec("SYS_1001", "INVALID_INPUT_DATA", HttpStatus.BAD_REQUEST);
        spec("SYS_1002", "UNAUTHORIZED", HttpStatus.UNAUTHORIZED);
        spec("SYS_1003", "ACCESS_DENIED", HttpStatus.FORBIDDEN);
        spec("SYS_1004", "SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        spec("SYS_1005", "OPTIMISTIC_LOCK_CONFLICT", HttpStatus.CONFLICT);

        spec("AUTH_1101", "USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("AUTH_1102", "PHONE_NUMBER_ALREADY_EXISTS", HttpStatus.CONFLICT);
        spec("AUTH_1103", "INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
        spec("AUTH_1104", "REFRESH_TOKEN_NOT_FOUND", HttpStatus.UNAUTHORIZED);
        spec("AUTH_1105", "REFRESH_TOKEN_REVOKED", HttpStatus.UNAUTHORIZED);
        spec("AUTH_1106", "REFRESH_TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED);
        spec("AUTH_1107", "ACCOUNT_DISABLED", HttpStatus.UNAUTHORIZED);
        spec("AUTH_1108", "VET_PROFILE_REQUIRED", HttpStatus.BAD_REQUEST);

        spec("APPT_1201", "PET_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("APPT_1202", "APPOINTMENT_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("APPT_1203", "SLOT_CAPACITY_EXCEEDED", HttpStatus.BAD_REQUEST);
        spec("APPT_1204", "CANNOT_CANCEL_APPOINTMENT", HttpStatus.BAD_REQUEST);
        spec("APPT_1205", "APPOINTMENT_NOT_CHECKABLE", HttpStatus.BAD_REQUEST);

        spec("CLIN_1301", "VISIT_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("CLIN_1302", "DOCTOR_NOT_AVAILABLE", HttpStatus.CONFLICT);
        spec("CLIN_1303", "QUEUE_EMPTY", HttpStatus.NOT_FOUND);
        spec("CLIN_1304", "PARACLINICAL_SERVICE_IN_PROGRESS", HttpStatus.BAD_REQUEST);
        spec("CLIN_1305", "INVALID_STATUS_TRANSITION", HttpStatus.BAD_REQUEST);
        spec("CLIN_1306", "PET_HAS_OPEN_VISIT", HttpStatus.CONFLICT);
        spec("CLIN_1307", "DIAGNOSIS_REQUIRED", HttpStatus.BAD_REQUEST);
        spec("CLIN_1308", "VITAL_SIGNS_REQUIRED", HttpStatus.BAD_REQUEST);
        spec("CLIN_1309", "IN_ROOM_SERVICE_PENDING", HttpStatus.BAD_REQUEST);
        spec("CLIN_1310", "REFUND_NOT_ALLOWED", HttpStatus.CONFLICT);
        spec("CLIN_1311", "NOT_ASSIGNED_DOCTOR", HttpStatus.FORBIDDEN);
        spec("CLIN_1312", "TICKET_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("CLIN_1313", "TICKET_NOT_WAITING", HttpStatus.CONFLICT);
        spec("CLIN_1314", "NO_QUEUE_TICKET", HttpStatus.BAD_REQUEST);
        spec("CLIN_1315", "SERVICE_PRICE_NOT_FOUND", HttpStatus.CONFLICT);
        spec("CLIN_1316", "DOCTOR_INACTIVE", HttpStatus.CONFLICT);
        spec("CLIN_1317", "MEDICAL_RECORD_LOCKED", HttpStatus.CONFLICT);
        spec("CLIN_1318", "QUEUE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);

        spec("BILL_1401", "INVOICE_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("BILL_1402", "DISCOUNT_EXCEEDS_INVOICE_AMOUNT", HttpStatus.BAD_REQUEST);
        spec("BILL_1403", "INVOICE_ALREADY_PAID", HttpStatus.CONFLICT);
        spec("BILL_1408", "INVOICE_ALREADY_CANCELLED", HttpStatus.CONFLICT);
        spec("BILL_1409", "VISIT_NOT_READY_FOR_PAYMENT", HttpStatus.BAD_REQUEST);
        spec("BILL_1410", "PRICE_EFFECTIVE_DATE_INVALID", HttpStatus.BAD_REQUEST);
        spec("PHARM_1404", "PRESCRIPTION_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("PHARM_1405", "MEDICINE_NOT_FOUND", HttpStatus.NOT_FOUND);
        spec("PHARM_1406", "INSUFFICIENT_STOCK", HttpStatus.CONFLICT);
        spec("PHARM_1407", "PRESCRIPTION_ALREADY_INVOICED", HttpStatus.CONFLICT);
        spec("PHARM_1411", "PRESCRIPTION_LOCKED", HttpStatus.CONFLICT);
        spec("PHARM_1412", "MEDICINE_PRICE_NOT_FOUND", HttpStatus.CONFLICT);
    }

    private static void spec(String code, String enumName, HttpStatus status) {
        DOCS_V4.put(code, new Spec(enumName, status));
    }

    @Test
    void docsCatalogHasExactly49Codes() {
        assertThat(DOCS_V4).hasSize(49);
    }

    @Test
    void everyDocsCodeExistsWithTheSameNameAndHttpStatus() {
        DOCS_V4.forEach((code, expected) -> {
            ErrorCode actual = ErrorCode.fromCode(code);
            assertThat(actual).as(code).isNotNull();
            assertThat(actual.name()).as(code + " name").isEqualTo(expected.enumName());
            assertThat(actual.getHttpStatus()).as(code + " status").isEqualTo(expected.status());
        });
    }

    @Test
    void codeNumbersAreUnique_soNoCodeOnlyInCodeCollidesWithDocs() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(seen.add(errorCode.getCode())).as("trùng mã " + errorCode.getCode()).isTrue();
        }
    }

    @Test
    void codesRenumberedByDecisionD3KeepTheirNewNumbers() {
        assertThat(ErrorCode.SPECIES_NOT_FOUND.getCode()).isEqualTo("CLIN_1325");
        assertThat(ErrorCode.LAB_ITEM_NOT_FOUND.getCode()).isEqualTo("CLIN_1326");
        assertThat(ErrorCode.CODE_ALREADY_EXISTS.getCode()).isEqualTo("CLIN_1327");
        assertThat(ErrorCode.INVALID_STOCK_ADJUSTMENT.getCode()).isEqualTo("PHARM_1415");
    }
}
