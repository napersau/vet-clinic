import { hasAnyRole, rolesOf } from './accessPolicy';
import { normalizeUser } from '../api/normalize';

describe('vai trò nhiều-nhiều', () => {
  test('rolesOf dùng roles, lùi về role đơn của dữ liệu cũ, rỗng nếu không có gì', () => {
    expect(rolesOf({ role: 'ADMIN', roles: ['VETERINARIAN', 'ADMIN'] })).toEqual(['VETERINARIAN', 'ADMIN']);
    expect(rolesOf({ role: 'RECEPTIONIST' })).toEqual(['RECEPTIONIST']);
    expect(rolesOf({ role: 'RECEPTIONIST', roles: [] })).toEqual(['RECEPTIONIST']);
    expect(rolesOf(null)).toEqual([]);
    expect(rolesOf({})).toEqual([]);
  });

  test('hasAnyRole khớp khi giao với vai trò được phép khác rỗng', () => {
    const doctorAdmin = { role: 'ADMIN', roles: ['ADMIN', 'VETERINARIAN'] };
    expect(hasAnyRole(doctorAdmin, ['VETERINARIAN'])).toBe(true);   // Bác sĩ kiêm Quản trị viên vào được khu bác sĩ
    expect(hasAnyRole(doctorAdmin, ['ADMIN'])).toBe(true);
    expect(hasAnyRole(doctorAdmin, ['RECEPTIONIST', 'PET_OWNER'])).toBe(false);
    expect(hasAnyRole(null, ['ADMIN'])).toBe(false);
  });

  test('normalizeUser luôn có roles (id vẫn là chuỗi, kể cả UUID)', () => {
    expect(normalizeUser({ id: '019a9442-d810-7000-8000-000000000002', role: 'VETERINARIAN', roles: ['VETERINARIAN'] }))
      .toMatchObject({ id: '019a9442-d810-7000-8000-000000000002', roles: ['VETERINARIAN'] });
    expect(normalizeUser({ id: 5, role: 'PET_OWNER' })).toMatchObject({ id: '5', roles: ['PET_OWNER'] });
  });
});
