package com.vetclinic.billing.service;

import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicinePrice;
import com.vetclinic.billing.repository.MedicinePriceRepository;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.billing.testsupport.TestData.CLOCK;
import static com.vetclinic.billing.testsupport.TestData.assertError;
import static com.vetclinic.billing.testsupport.TestData.medicine;
import static com.vetclinic.billing.testsupport.TestData.mid;
import static com.vetclinic.billing.testsupport.TestData.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicinePriceBookTest {

    private static final Instant NOW = CLOCK.instant();
    private static final UUID ADMIN_ID_FOR_PRICES = uid(1);
    private static final Medicine AMOX = medicine(1L, "Clavamox", "15000", 200);

    @Mock
    private MedicinePriceRepository priceRepository;

    private MedicinePriceBook book() {
        return new MedicinePriceBook(priceRepository, CLOCK);
    }

    private static MedicinePrice open(String price, Instant from) {
        return MedicinePrice.builder().medicine(AMOX).salePrice(new BigDecimal(price)).vatRate(new BigDecimal("0.00")).effectiveFrom(from).build();
    }

    @Test
    void current_returnsThePriceEffectiveNow() {
        MedicinePrice price = open("15000.00", Instant.parse("2026-01-01T00:00:00Z"));
        when(priceRepository.findEffective(mid(1), NOW)).thenReturn(Optional.of(price));

        assertThat(book().current(mid(1))).isSameAs(price);
    }

    @Test
    void current_withoutAnEffectivePriceIsRefused() {
        UUID unpriced = mid(99);
        when(priceRepository.findEffective(unpriced, NOW)).thenReturn(Optional.empty());

        assertError(ErrorCode.MEDICINE_PRICE_NOT_FOUND, () -> book().current(unpriced));   // PHARM_1412: không lập hóa đơn khi chưa có giá
    }

    @Test
    void changePrice_closesTheOpenRowWithoutTouchingItsAmountAndAddsANewOne() {
        MedicinePrice old = open("15000.00", Instant.parse("2026-01-01T00:00:00Z"));
        when(priceRepository.findByMedicineIdAndEffectiveToIsNull(mid(1))).thenReturn(Optional.of(old));
        when(priceRepository.saveAndFlush(any(MedicinePrice.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant tomorrow = NOW.plusSeconds(86400);

        MedicinePrice created = book().changePrice(AMOX, new BigDecimal("18000.00"), new BigDecimal("0.05"), tomorrow, "Điều chỉnh giá", ADMIN_ID_FOR_PRICES);

        assertThat(old.getEffectiveTo()).isEqualTo(tomorrow);                      // chỉ đóng dòng cũ
        assertThat(old.getSalePrice()).isEqualByComparingTo("15000.00");             // không sửa số tiền cũ (BR-38)
        assertThat(created.getSalePrice()).isEqualByComparingTo("18000.00");
        assertThat(created.getVatRate()).isEqualByComparingTo("0.05");
        assertThat(created.getEffectiveFrom()).isEqualTo(tomorrow);
        assertThat(created.getEffectiveTo()).isNull();                             // dòng mới là dòng mở
        assertThat(created.getCreatedBy()).isEqualTo(ADMIN_ID_FOR_PRICES);
        assertThat(created.getChangeReason()).isEqualTo("Điều chỉnh giá");
        InOrder order = inOrder(priceRepository);   // đóng dòng cũ trước rồi mới thêm dòng mới
        order.verify(priceRepository).saveAndFlush(old);
        order.verify(priceRepository).saveAndFlush(created);
    }

    @Test
    void changePrice_truncatesTheEffectiveInstantToWholeSecondsBecauseTheColumnIsDatetime() {
        MedicinePrice old = open("15000.00", Instant.parse("2026-01-01T00:00:00Z"));
        when(priceRepository.findByMedicineIdAndEffectiveToIsNull(mid(1))).thenReturn(Optional.of(old));
        when(priceRepository.saveAndFlush(any(MedicinePrice.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant withFraction = Instant.parse("2026-11-01T10:00:00.900Z");   // MySQL sẽ làm tròn lên 10:00:01 nếu không cắt

        MedicinePrice created = book().changePrice(AMOX, new BigDecimal("1.00"), new BigDecimal("0.00"), withFraction, null, ADMIN_ID_FOR_PRICES);

        assertThat(created.getEffectiveFrom()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
        assertThat(old.getEffectiveTo()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void changePrice_effectiveDateNotAfterTheOpenRowStartIsRefused() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        MedicinePrice old = open("15000.00", from);
        when(priceRepository.findByMedicineIdAndEffectiveToIsNull(mid(1))).thenReturn(Optional.of(old));

        assertError(ErrorCode.PRICE_EFFECTIVE_DATE_INVALID,   // BILL_1410
                () -> book().changePrice(AMOX, new BigDecimal("1.00"), new BigDecimal("0.00"), from, null, ADMIN_ID_FOR_PRICES));
        assertError(ErrorCode.PRICE_EFFECTIVE_DATE_INVALID,
                () -> book().changePrice(AMOX, new BigDecimal("1.00"), new BigDecimal("0.00"), from.minusSeconds(60), null, ADMIN_ID_FOR_PRICES));
        assertThat(old.getEffectiveTo()).isNull();
        verify(priceRepository, never()).saveAndFlush(any());
    }

    @Test
    void changePrice_serviceWithoutAnyOpenRowIsRefused() {
        when(priceRepository.findByMedicineIdAndEffectiveToIsNull(mid(1))).thenReturn(Optional.empty());

        assertError(ErrorCode.MEDICINE_PRICE_NOT_FOUND,
                () -> book().changePrice(AMOX, new BigDecimal("1.00"), new BigDecimal("0.00"), NOW, null, ADMIN_ID_FOR_PRICES));
    }

    @Test
    void openFirstPrice_startsNow() {
        when(priceRepository.saveAndFlush(any(MedicinePrice.class))).thenAnswer(inv -> inv.getArgument(0));

        MedicinePrice first = book().openFirstPrice(AMOX, new BigDecimal("15000.00"), new BigDecimal("0.00"), "Bảng giá khởi tạo", ADMIN_ID_FOR_PRICES);

        ArgumentCaptor<MedicinePrice> saved = ArgumentCaptor.forClass(MedicinePrice.class);
        verify(priceRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue()).isSameAs(first);
        assertThat(first.getEffectiveFrom()).isEqualTo(NOW);
        assertThat(first.getEffectiveTo()).isNull();
    }
}
