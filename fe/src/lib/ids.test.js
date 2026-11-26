import { idOrNull, sameId } from './ids';

describe('id không phụ thuộc kiểu', () => {
  it('so sánh số với chuỗi số và UUID chuỗi', () => {
    expect(sameId(5, '5')).toBe(true);
    expect(sameId('5', 5)).toBe(true);
    expect(sameId(5, 6)).toBe(false);
    expect(sameId('019a9442-d810-7000-8000-000000000002', '019a9442-d810-7000-8000-000000000002')).toBe(true);
    expect(sameId('019a9442-d810-7000-8000-000000000002', '019a9442-d810-7000-8000-000000000003')).toBe(false);
  });

  it('id rỗng không bao giờ bằng gì (kể cả bằng nhau)', () => {
    expect(sameId(null, null)).toBe(false);
    expect(sameId(undefined, undefined)).toBe(false);
    expect(sameId('', '')).toBe(false);
    expect(sameId(null, '')).toBe(false);
    expect(sameId(0, '0')).toBe(true);
  });

  it('idOrNull giữ nguyên kiểu, chỉ đổi giá trị rỗng thành null', () => {
    expect(idOrNull('')).toBeNull();
    expect(idOrNull(undefined)).toBeNull();
    expect(idOrNull('7')).toBe('7');
    expect(idOrNull(7)).toBe(7);
    expect(idOrNull('019a9442-d810-7000-8000-000000000002')).toBe('019a9442-d810-7000-8000-000000000002');
  });
});
