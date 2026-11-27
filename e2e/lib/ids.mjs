// Tra id của dữ liệu seed qua MÃ ỔN ĐỊNH thay vì viết cứng số (bước 0.2 lộ trình v4):
// tài khoản theo username, thú cưng theo petCode, loài / dịch vụ / thuốc theo code. Khi các Phase sau đổi khóa sang UUIDv7,
// e2e chỉ cần sửa file này (và vài câu SQL), không phải sửa từng kiểm tra.
// Kết quả được cache trong lần chạy; tra cứu dùng token của admin.
import { AUTH, CLIN, PHARM, call, login } from './api.mjs';

/** Mã ổn định của dữ liệu seed (`docs/seed_demo_data.sql`) mà các bộ e2e dùng. */
export const SEED = {
  users: ['admin', 'bs_tien', 'bs_an', 'bs_mai', 'letan_hoa', 'ktv_lab_minh', 'ktv_cdha_dung', 'khach_lan', 'khach_nam'],
  pets: { lu: 'PET-2026-0001', milo: 'PET-2026-0002' },
  species: ['DOG', 'CAT'],
  services: ['EXAM_GEN', 'VAC_7B', 'VAC_4B', 'DEWORM', 'LAB_CBC', 'IMG_US_AB', 'IMG_XRAY'],
  medicines: ['MED_AMOX', 'MED_VANG_7B', 'MED_PURE_4B', 'MED_DRON_DOG'],
};

let adminToken;
const cache = new Map();
const adminTokenOnce = async () => { adminToken ??= (await login('admin')).accessToken; return adminToken; };

async function list(url) {
  const res = await call('GET', url, { token: await adminTokenOnce() });
  if (res.status !== 200) throw new Error(`Không tra được ${url}: ${res.status} ${res.message || ''}`);
  return Array.isArray(res.data) ? res.data : res.data?.items || [];
}

// Tìm theo từ khóa rồi lọc khớp TUYỆT ĐỐI trên trường mã (từ khóa là LIKE: PET-2026-0001 còn khớp PET-2026-00019)
function lookup(kind, field, value, urls) {
  const key = `${kind}:${value}`;
  if (!cache.has(key)) {
    cache.set(key, (async () => {
      for (const url of urls) {
        const hit = (await list(url)).find((item) => item[field] === value);
        if (hit) return hit.id;
      }
      throw new Error(`Không tìm thấy ${kind} có ${field} = ${value} (dữ liệu seed đã nạp chưa?)`);
    })());
  }
  return cache.get(key);
}

const q = encodeURIComponent;
export const ids = {
  user: (username) => lookup('tài khoản', 'username', username, [`${AUTH}/users?keyword=${q(username)}&size=100`]),
  pet: (petCode) => lookup('thú cưng', 'petCode', petCode, [`/api/v1/pets?keyword=${q(petCode)}&size=100`]),
  species: (code) => lookup('loài', 'code', code, ['/api/v1/species']),
  service: (code) => lookup('dịch vụ', 'code', code, ['/api/v1/catalog/services?includeInactive=true']),
  medicine: (code) => lookup('thuốc', 'code', code, [`${PHARM}/medicines?keyword=${q(code)}&size=100`, `${PHARM}/medicines?size=100`]),
};

async function resolveAll(codes, resolver) {
  const entries = Array.isArray(codes) ? codes.map((code) => [code, code]) : Object.entries(codes);
  return Object.fromEntries(await Promise.all(entries.map(async ([name, code]) => [name, await resolver(code)])));
}

/**
 * Tra một lần toàn bộ id seed mà e2e dùng:
 * `{ user: { bs_tien, ... }, pet: { lu, milo }, species: { DOG, CAT }, service: { LAB_CBC, ... }, medicine: { MED_AMOX, ... } }`.
 */
export async function seedIds() {
  const [user, pet, species, service, medicine] = await Promise.all([
    resolveAll(SEED.users, ids.user),
    resolveAll(SEED.pets, ids.pet),
    resolveAll(SEED.species, ids.species),
    resolveAll(SEED.services, ids.service),
    resolveAll(SEED.medicines, ids.medicine),
  ]);
  return { user, pet, species, service, medicine };
}

/** Câu con SQL lấy id thuốc theo mã (để truy vấn đối chiếu không phụ thuộc kiểu khóa). */
export const medicineIdSql = (code) => `(SELECT id FROM pet_clinic_billing_pharmacy_db.medicines WHERE code='${code}')`;

/**
 * Biểu thức SQL cho một id người dùng (UUID dạng chuỗi) so với cột BINARY(16): `doctor_id = ${uuidSql(id)}`.
 * Từ bước 2.1 mọi cột tham chiếu người dùng là BINARY(16), nên không thể viết `doctor_id = 2` như v3.
 */
export const uuidSql = (id) => `UNHEX(REPLACE('${id}','-',''))`;
