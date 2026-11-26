package com.vetclinic.common.feign;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Điều kiện tính thất bại của Circuit Breaker (bước 8.1): chỉ lỗi hạ tầng và 5xx; lỗi nghiệp vụ 4xx của service con thì không. */
class FeignFailurePredicateTest {

    private final FeignFailurePredicate predicate = new FeignFailurePredicate();

    @Test
    void businessErrorsFromTheDownstreamServiceDoNotCount() {
        assertThat(predicate.test(new AppException(ErrorCode.PET_NOT_FOUND))).isFalse();            // 404
        assertThat(predicate.test(new AppException(ErrorCode.INVALID_INPUT_DATA))).isFalse();       // 400
        assertThat(predicate.test(new AppException(ErrorCode.ACCESS_DENIED))).isFalse();            // 403
        assertThat(predicate.test(new AppException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT))).isFalse(); // 409
    }

    @Test
    void serverErrorsAndInfrastructureFailuresCount() {
        assertThat(predicate.test(new AppException(ErrorCode.SERVICE_UNAVAILABLE))).isTrue();   // 503
        assertThat(predicate.test(new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION))).isTrue(); // 500
        assertThat(predicate.test(new IOException("connection refused"))).isTrue();
        assertThat(predicate.test(new IllegalStateException("timeout"))).isTrue();
    }

    @Test
    void looksThroughWrappersToTheRealCause() {
        assertThat(predicate.test(new ExecutionException(new AppException(ErrorCode.PET_NOT_FOUND)))).isFalse();
        assertThat(predicate.test(new RuntimeException("bọc", new AppException(ErrorCode.SERVICE_UNAVAILABLE)))).isTrue();
    }
}
