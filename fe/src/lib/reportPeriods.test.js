import { groupRevenue, periodOf } from './reportPeriods';

const line = (businessDate, revenueType, netAmount, lineCount = 1) => ({ businessDate, revenueType, lineCount, amountBeforeVat: netAmount, vatAmount: 0, discountAmount: 0, netAmount });

describe('periodOf', () => {
  it('nhãn theo ngày, tháng, quý', () => {
    expect(periodOf('2026-11-21', 'DAY')).toEqual({ key: '2026-11-21', label: '21/11/2026' });
    expect(periodOf('2026-11-21', 'MONTH')).toEqual({ key: '2026-11', label: '11/2026' });
    expect(periodOf('2026-11-21', 'QUARTER')).toEqual({ key: '2026-Q4', label: 'Quý 4/2026' });
    expect(periodOf('2026-03-31', 'QUARTER').key).toBe('2026-Q1');
  });

  it('tuần bắt đầu từ thứ Hai (21/11/2026 là thứ Bảy -> tuần 16/11)', () => {
    expect(periodOf('2026-11-21', 'WEEK').key).toBe('2026-11-16');
    expect(periodOf('2026-11-16', 'WEEK').key).toBe('2026-11-16');
    expect(periodOf('2026-11-22', 'WEEK').key).toBe('2026-11-16');   // Chủ nhật vẫn thuộc tuần đó
    expect(periodOf('2026-11-23', 'WEEK').key).toBe('2026-11-23');
    expect(periodOf('2026-01-01', 'WEEK').key).toBe('2025-12-29');   // tuần vắt qua năm
  });
});

describe('groupRevenue', () => {
  const items = [line('2026-11-21', 'SERVICE', 535000, 3), line('2026-11-21', 'MEDICINE', 157500), line('2026-11-22', 'SERVICE', -180000, -1), line('2026-12-01', 'SERVICE', 100000)];

  it('gom theo ngày: tách dịch vụ / thuốc, hoàn tiền (số âm) trừ vào tổng', () => {
    const rows = groupRevenue(items, 'DAY');
    expect(rows.map((r) => r.key)).toEqual(['2026-11-21', '2026-11-22', '2026-12-01']);
    expect(rows[0]).toMatchObject({ serviceNet: 535000, medicineNet: 157500, netAmount: 692500, lineCount: 4 });
    expect(rows[1]).toMatchObject({ serviceNet: -180000, netAmount: -180000, lineCount: -1 });
  });

  it('gom theo tháng và quý giữ nguyên tổng', () => {
    const months = groupRevenue(items, 'MONTH');
    expect(months.map((r) => [r.label, r.netAmount])).toEqual([['11/2026', 512500], ['12/2026', 100000]]);
    const quarters = groupRevenue(items, 'QUARTER');
    expect(quarters).toHaveLength(1);
    expect(quarters[0].netAmount).toBe(612500);
  });

  it('không có dòng thì trả mảng rỗng', () => {
    expect(groupRevenue([], 'WEEK')).toEqual([]);
  });
});
