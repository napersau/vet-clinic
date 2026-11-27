// Hàm dùng chung cho các bộ e2e: gọi API qua Gateway, đăng nhập, đếm PASS/FAIL, truy vấn MySQL trong container.
// Mọi bộ e2e import từ đây thay vì tự định nghĩa lại (trước v4 mỗi file có một bản sao riêng).
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const API = process.env.API_URL || 'http://localhost:8080';
export const PASSWORD = 'Admin@123';   // mật khẩu chung của mọi tài khoản seed
export const RUN = Date.now().toString().slice(-6);   // hậu tố để tên / mã / SĐT tạo trong lần chạy không trùng lần trước

export const AUTH = '/api/v1/auth';
export const CLIN = '/api/v1/clinical';
export const PHARM = '/api/v1/pharmacy';
export const BILL = '/api/v1/billing';
export const APPT = '/api/v1/appointments';
export const NOTI = '/api/v1/notifications';
export const REPORTS = '/api/v1/reports';   // reporting-service (v4.0, bước 7.2)

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------- đếm kết quả ----------
let passed = 0;
const failures = [];
export const check = (name, ok, extra = '') => {
  if (ok) { passed += 1; console.log(`  PASS ${name}`); } else { failures.push(name); console.log(`  FAIL ${name} ${extra}`); }
};
export const step = (title) => console.log(`\n== ${title}`);
/** In tổng kết và thoát: 0 nếu không có lỗi, 1 nếu có kiểm tra FAIL. */
export function finish() {
  console.log(`\n${passed} đạt, ${failures.length} lỗi${failures.length ? `: ${failures.join('; ')}` : ''}`);
  process.exit(failures.length ? 1 : 0);
}
/** Chạy hàm main của một bộ e2e; lỗi ngoài dự kiến (không phải FAIL) thoát mã 2. */
export function run(main) {
  main().catch((problem) => { console.error(problem); process.exit(2); });
}

// ---------- HTTP ----------
/** Gọi API qua Gateway; trả `{ status, code, data, message }` (code = errorCode của ApiResponse). */
export async function call(method, url, { token, body, form } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${API}${url}`, { method, headers, body: form || (body === undefined ? undefined : JSON.stringify(body)) });
  const text = await response.text();
  let json = {};
  try { json = text ? JSON.parse(text) : {}; } catch { json = { message: text }; }
  return { status: response.status, code: json.errorCode, data: json.data, message: json.message };
}
export const brief = (res) => `${res.status}${res.code ? `/${res.code}` : ''} ${String(res.message || '').slice(0, 120)}`;

export const loginRes = (username, password = PASSWORD) => call('POST', `${AUTH}/login`, { body: { username, password } });
/** Đăng nhập, trả `data` của phản hồi (accessToken, refreshToken, user). */
export async function login(username, password = PASSWORD) {
  const res = await loginRes(username, password);
  if (res.status !== 200) throw new Error(`Không đăng nhập được ${username}: ${brief(res)}`);
  return res.data;
}

// ---------- Redis (hàng đợi, bước 6.2) ----------
/** Chạy một lệnh redis-cli trong container Redis của docker compose (đọc phiếu xếp hàng: ticket:{id}, queue:..., queue:visit:{id}). */
export function redis(...args) {
  return execSync(`docker compose exec -T redis redis-cli ${args.join(' ')}`, { cwd: ROOT, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
}

// ---------- CSDL ----------
/** Truy vấn MySQL trong container (đối chiếu VIEW, bất biến kho...). Cần docker compose và .env ở thư mục gốc. */
export function sql(query) {
  const env = fs.readFileSync(path.join(ROOT, '.env'), 'utf8');
  const password = /^MYSQL_ROOT_PASSWORD=(.*)$/m.exec(env)?.[1]?.trim();
  return execSync(`docker compose exec -T mysql-server mysql -uroot -p${password} -N -B -e "${query.replace(/"/g, '\\"')}"`,
    { cwd: ROOT, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
}
