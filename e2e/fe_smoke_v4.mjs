// Kiểm thử giao diện thật v3.0 (FE -> Gateway -> các service) bằng Chrome headless qua Chrome DevTools Protocol.
// Chạy: node e2e/fe_smoke_v4.mjs   (cần: Gateway :8080 + các service đang chạy, `npm run build` trong fe/ đã có thư mục build/, Chrome cài sẵn)
// Kịch bản đi trọn một ca khám v3.0 qua 6 vai trò: khách xem khung giờ trống công khai -> chủ nuôi đăng ký + đặt lịch -> lễ tân duyệt +
// tiếp nhận kèm chọn bác sĩ -> bác sĩ gọi số / đẩy lùi / khám / dịch vụ tại phòng / chỉ định CLS -> KTV Lab, CĐHA gọi số + nhập kết quả ->
// bác sĩ giai đoạn 2 + kê đơn (cảnh báo tồn) + chốt ca -> lễ tân thu từng phần hóa đơn dịch vụ + bán thuốc -> chủ nuôi xem bệnh án + thông báo ->
// admin xem báo cáo hai nguồn doanh thu.
// CẢNH BÁO: tạo dữ liệu thật (tài khoản, lịch hẹn, hóa đơn). Chỉ chạy trên môi trường thử nghiệm.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const feDir = path.resolve(here, '../fe');
const WebSocket = createRequire(path.join(feDir, 'package.json'))('ws');
const API = process.env.API_URL || 'http://localhost:8080';
const FE_PORT = Number(process.env.FE_PORT || 3000);
const FE = `http://localhost:${FE_PORT}`;
const CHROME = process.env.CHROME_PATH || ['C:/Program Files/Google/Chrome/Application/chrome.exe', '/usr/bin/google-chrome', '/usr/bin/chromium'].find((p) => fs.existsSync(p));
const DEBUG_PORT = 9333;
const RUN = Date.now().toString().slice(-6);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let passed = 0; const failures = [];
const check = (name, ok, extra = '') => { if (ok) { passed += 1; console.log(`  PASS ${name}`); } else { failures.push(name); console.log(`  FAIL ${name} ${extra}`); } };
const step = (title) => console.log(`\n== ${title}`);

// ---------- API (chuẩn bị tài khoản nhân sự) ----------
async function api(method, url, body, token) {
  const response = await fetch(`${API}${url}`, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : {} };
}
const STAFF = {
  vet: { roleCode: 'VETERINARIAN', fullName: 'BS Smoke', phoneNumber: '0990000001', roomNumber: '951' },
  reception: { roleCode: 'RECEPTIONIST', fullName: 'Le Tan Smoke', phoneNumber: '0990000002' },
  lab: { roleCode: 'LAB_TECHNICIAN', fullName: 'KTV XN Smoke', phoneNumber: '0990000003', roomNumber: '952' },
  imaging: { roleCode: 'IMAGING_TECHNICIAN', fullName: 'KTV CDHA Smoke', phoneNumber: '0990000004', roomNumber: '953' },
};
const PASSWORD = 'Smoke@1234';
async function prepareAccounts() {
  const login = await api('POST', '/api/v1/auth/login', { username: 'admin', password: 'Admin@123' });
  const token = login.body.data.accessToken;
  for (const { roleCode, ...account } of Object.values(STAFF)) {
    // Nhân sự tạo qua POST /api/v1/staff (v4.0); Bác sĩ bắt buộc có hồ sơ chuyên môn (AUTH_1108)
    const vetProfile = roleCode === 'VETERINARIAN'
      ? { licenseNumber: `SMOKE-${account.phoneNumber}`, licenseIssuedDate: '2020-01-01', licenseIssuedBy: 'Cục Thú y', academicDegree: 'Bác sĩ thú y' }
      : undefined;
    const created = await api('POST', '/api/v1/staff', { ...account, roleCodes: [roleCode], password: PASSWORD, username: account.phoneNumber, vetProfile }, token);
    if (created.status >= 300) {
      // đã tồn tại từ lần chạy trước: đặt lại mật khẩu và mở khóa (mở khóa tài khoản đang ACTIVE trả 400: bỏ qua)
      const found = await api('GET', `/api/v1/staff?keyword=${account.phoneNumber}`, undefined, token);
      const user = found.body.data?.items?.[0];
      if (user) { await api('POST', `/api/v1/auth/users/${user.id}/reset-password`, { newPassword: PASSWORD }, token); await api('POST', `/api/v1/users/${user.id}/unlock`, undefined, token); }
    }
  }
}

// ---------- máy chủ tĩnh cho bản build (nếu cổng FE chưa có ai nghe) ----------
async function ensureFrontend() {
  const alive = await fetch(FE).then(() => true).catch(() => false);
  if (alive) return null;
  const root = path.join(feDir, 'build');
  if (!fs.existsSync(path.join(root, 'index.html'))) throw new Error('Chưa có fe/build — chạy `npm run build` trong fe/ trước.');
  const types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.json': 'application/json', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.jpg': 'image/jpeg' };
  const server = http.createServer((req, res) => {
    let file = path.join(root, decodeURIComponent(new URL(req.url, FE).pathname));
    if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'index.html');
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  await new Promise((resolve) => server.listen(FE_PORT, resolve));
  return server;
}

// ---------- CDP ----------
class Browser {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.problems = []; this.requests = new Map();
    ws.on('message', (raw) => {
      const message = JSON.parse(raw);
      if (message.id && this.pending.has(message.id)) { const { resolve, reject } = this.pending.get(message.id); this.pending.delete(message.id); message.error ? reject(new Error(message.error.message)) : resolve(message.result); return; }
      if (message.method === 'Runtime.exceptionThrown') this.problems.push(`exception: ${message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text}`);
      if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') this.problems.push(`console.error: ${message.params.args.map((a) => a.value ?? a.description).join(' ').slice(0, 200)}`);
      if (message.method === 'Network.requestWillBeSent') this.requests.set(message.params.requestId, message.params.request.url);
      if (message.method === 'Network.responseReceived' && message.params.response.status >= 400) this.problems.push(`HTTP ${message.params.response.status} ${message.params.response.url.replace(API, '')}`);
    });
  }
  send(method, params = {}) { const id = ++this.id; return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.ws.send(JSON.stringify({ id, method, params })); }); }
  async eval(expression) {
    const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  }
  async goto(route) { await this.send('Page.navigate', { url: `${FE}${route}` }); await sleep(600); await this.waitFor('document.readyState === "complete"', 15000); }
  async waitFor(expression, timeout = 12000) {
    const end = Date.now() + timeout;
    while (Date.now() < end) { try { if (await this.eval(`Boolean(${expression})`)) return true; } catch { /* trang đang chuyển */ } await sleep(250); }
    return false;
  }
  text() { return this.eval('document.body.innerText'); }
  async hasText(text, timeout = 12000) { return this.waitFor(`document.body.innerText.includes(${JSON.stringify(text)})`, timeout); }
  async click(text, scope = 'button, a, [role="tab"]') {
    const ok = await this.waitFor(`(${findByText})(${JSON.stringify(text)}, ${JSON.stringify(scope)})`, 10000);
    if (!ok) throw new Error(`Không thấy nút/liên kết "${text}"`);
    await this.eval(`(${findByText})(${JSON.stringify(text)}, ${JSON.stringify(scope)}).click()`);
    await sleep(350);
  }
  async clickRow(rowText, buttonText) {
    const script = `(() => { const rows = [...document.querySelectorAll('tr, .ui-list > li')].filter((r) => r.innerText.includes(${JSON.stringify(rowText)}) && ![...r.querySelectorAll('tr, li')].some((c) => c.innerText.includes(${JSON.stringify(rowText)})));
      for (const row of rows) { const el = [...row.querySelectorAll('button, a')].find((x) => !x.disabled && x.textContent.replace(/\\s+/g, ' ').trim() === ${JSON.stringify(buttonText)}); if (el) return el; } return null; })()`;
    if (!(await this.waitFor(script, 12000))) throw new Error(`Không thấy nút "${buttonText}" ở hàng chứa "${rowText}". Trang: ${(await this.text()).replace(/\s+/g, ' ').slice(0, 1200)}`);
    await this.eval(script + '.click()');
    await sleep(350);
  }
  async fill(label, value) {
    const ok = await this.waitFor(`(${findField})(${JSON.stringify(label)})`, 10000);
    if (!ok) throw new Error(`Không thấy ô nhập "${label}"`);
    // ô chọn nạp lựa chọn bất đồng bộ: thử lại tới khi có lựa chọn cần chọn
    const end = Date.now() + 10000;
    for (;;) {
      try { await this.eval(`(${setValue})((${findField})(${JSON.stringify(label)}), ${JSON.stringify(String(value))})`); break; } catch (problem) {
        if (!/Không có lựa chọn/.test(problem.message) || Date.now() > end) throw problem;
        await sleep(300);
      }
    }
    await sleep(120);
  }
  async setFiles(label, files) {
    const { root } = await this.send('DOM.getDocument');
    const { nodeIds } = await this.send('DOM.querySelectorAll', { nodeId: root.nodeId, selector: 'input[type=file]' });
    if (!nodeIds.length) throw new Error(`Không thấy ô chọn tệp "${label}"`);
    await this.send('DOM.setFileInputFiles', { nodeId: nodeIds[0], files });
    await sleep(300);
  }
  async reset() { await this.goto('/auth/login'); await this.eval('localStorage.clear(); sessionStorage.clear()'); }
  async login(username, password) {
    await this.reset(); await this.goto('/auth/login');
    await this.fill('Tên đăng nhập hoặc số điện thoại', username); await this.fill('Mật khẩu', password);
    await this.click('Đăng nhập', 'button[type=submit]');
    return this.waitFor('!location.pathname.startsWith("/auth/login")', 15000);
  }
}

// Các hàm chạy TRONG trang (được stringify), nên chỉ dùng DOM.
function findByText(text, scope) {
  const visible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const root = document.querySelector('dialog[open]') || document;
  const norm = (value) => value.replace(/\s+/g, ' ').trim();
  const items = [...root.querySelectorAll(scope)].filter((el) => visible(el) && !el.disabled);
  return items.find((el) => norm(el.textContent) === text) || items.find((el) => norm(el.textContent).includes(text));
}
function findField(label) {
  const root = document.querySelector('dialog[open]') || document;
  const norm = (value) => value.replace(/\s+/g, ' ').trim();
  const byAria = [...root.querySelectorAll('input,select,textarea')].find((el) => (el.getAttribute('aria-label') || '').startsWith(label));
  if (byAria) return byAria;
  const labels = [...root.querySelectorAll('label')].filter((el) => { const head = el.querySelector(':scope > span'); return head && norm(head.textContent).startsWith(label); });
  const control = labels.length && labels[0].querySelector('input,select,textarea');
  if (control) return control;
  return [...root.querySelectorAll('input,textarea')].find((el) => (el.placeholder || '').includes(label));
}
function setValue(el, value) {
  const proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  if (el.tagName === 'SELECT') {
    const option = [...el.options].find((o) => o.textContent.trim() === value) || [...el.options].find((o) => o.textContent.includes(value)) || [...el.options].find((o) => o.value === value);
    if (!option) throw new Error(`Không có lựa chọn "${value}"`);
    value = option.value;
  }
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
  el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
}

async function launchChrome() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'fe-smoke-'));
  const child = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${DEBUG_PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', '--window-size=1400,1000', 'about:blank'], { stdio: 'ignore' });
  for (let i = 0; i < 40; i += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)).json();
      const page = targets.find((t) => t.type === 'page');
      if (page) { const ws = new WebSocket(page.webSocketDebuggerUrl); await new Promise((resolve) => ws.on('open', resolve)); const browser = new Browser(ws); for (const domain of ['Page', 'Runtime', 'Network', 'DOM']) await browser.send(`${domain}.enable`); return { browser, child, profile }; }
    } catch { /* chờ Chrome khởi động */ }
    await sleep(500);
  }
  throw new Error('Không khởi động được Chrome');
}

function writePng() {
  // PNG 1x1 hợp lệ (magic byte đúng — backend kiểm tra nội dung tệp, không tin phần mở rộng)
  const file = path.join(os.tmpdir(), `smoke-${RUN}.png`);
  fs.writeFileSync(file, Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64'));
  return file;
}

// ---------- kịch bản v3.0 ----------
async function main() {
  if (!CHROME) throw new Error('Không tìm thấy Chrome; đặt CHROME_PATH.');
  await prepareAccounts();
  const server = await ensureFrontend();
  const { browser: b, child, profile } = await launchChrome();
  const owner = { name: `Chu Smoke ${RUN}`, phone: `098${RUN}1`.slice(0, 10), password: 'Owner@1234', pet: `Milo${RUN}` };
  const png = writePng();
  const checkbox = (labelText, scope = 'label.ui-check') => b.eval(`[...document.querySelectorAll(${JSON.stringify(scope)})].find(l => l.innerText.includes(${JSON.stringify(labelText)})).querySelector("input").click()`);
  try {
    step('0. Công khai (chưa đăng nhập): khung giờ trống qua Gateway');
    await b.reset(); await b.goto('/');
    check('trang chủ có mục khung giờ còn trống', await b.hasText('Khung giờ còn trống'));
    check('khung giờ tải được không cần đăng nhập', await b.waitFor('document.querySelectorAll("#availability [role=listitem]").length > 0', 15000), (await b.text()).slice(0, 200));

    step('1. Chủ nuôi: đăng ký, thêm thú cưng, đặt lịch');
    await b.reset(); await b.goto('/auth/register');
    await b.fill('Họ và tên', owner.name); await b.fill('Số điện thoại', owner.phone);
    await b.fill('Mật khẩu', owner.password); await b.fill('Xác nhận mật khẩu', owner.password);
    await b.eval('document.querySelector("input[name=terms]").click()');
    await b.click('Tạo tài khoản', 'button[type=submit]');
    check('đăng ký xong và vào được cổng khách hàng', await b.waitFor('!location.pathname.startsWith("/auth/")', 15000), await b.text());
    await b.goto('/portal/pets');
    await b.click('+ Thêm thú cưng'); await b.fill('Tên thú cưng', owner.pet); await b.fill('Loài', 'Chó'); await b.click('Thêm thú cưng', 'button[type=submit]');
    check('thêm thú cưng thành công', await b.hasText(`Đã lưu hồ sơ của ${owner.pet}`), (await b.text()).slice(0, 300));
    await b.goto('/portal/booking');
    await b.fill('Thú cưng', owner.pet); await b.fill('Lý do khám', 'Bỏ ăn hai ngày');
    check('có khung giờ trống', await b.waitFor('document.querySelector(".ui-slot:not([disabled])")'));
    await b.eval('document.querySelector(".ui-slot:not([disabled])").click()');
    await b.click('Gửi yêu cầu đặt lịch', 'button[type=submit]');
    check('gửi yêu cầu đặt lịch', await b.hasText('Đã gửi yêu cầu đặt lịch'), (await b.text()).slice(0, 300));

    step('2. Lễ tân: duyệt lịch, tiếp nhận kèm chọn bác sĩ, thu phí khám → phát phiếu');
    check('lễ tân đăng nhập', await b.login(STAFF.reception.phoneNumber, PASSWORD));
    await b.goto('/reception/appointments');
    check('thấy lịch chờ duyệt của chủ nuôi', await b.hasText(owner.name));
    await b.clickRow(owner.name, 'Xác nhận');
    check('xác nhận lịch hẹn', await b.hasText('Đã xác nhận lịch'));
    await b.goto('/reception/intake'); await b.click('Khách vãng lai', '[role=tab]');
    await b.fill('Tìm chủ nuôi', owner.phone);
    check('tìm thấy chủ nuôi theo SĐT', await b.waitFor(`[...document.querySelectorAll(".ui-list li")].some(li => li.innerText.includes(${JSON.stringify(owner.name)}))`));
    await b.click('Chọn');
    check('tự chọn thú cưng duy nhất', await b.waitFor(`document.querySelector("select.ui-input") && document.querySelector("select.ui-input").selectedOptions[0].textContent.includes(${JSON.stringify(owner.pet)})`));
    await b.fill('Lý do đến khám', 'Bỏ ăn, nôn');
    await b.fill('Bác sĩ phụ trách', STAFF.vet.fullName);
    await b.click('Tiếp nhận', 'button[type=submit]');
    check('tiếp nhận thành công, chờ nộp phí khám (chưa có phiếu)', await b.hasText('Chờ nộp phí khám') && await b.hasText('Nộp phí khám xong sẽ được phát phiếu'), (await b.text()).slice(0, 400));
    await b.goto('/reception/queue');
    check('bảng điều phối: ca ở mục "Chờ nộp phí khám"', await b.hasText('Chờ nộp phí khám') && await b.hasText(owner.pet), (await b.text()).slice(0, 400));
    await b.clickRow(owner.pet, 'Thu phí khám');
    check('màn hình thu ngân: phí khám thu trước', await b.hasText('Khám lâm sàng') && await b.hasText('thu trước'), (await b.text()).slice(0, 400));
    await b.click('hóa đơn đã chọn', 'button'); await b.click('Đã thu tiền', 'button[type=submit]');
    check('thu phí khám → phiếu vào hàng đợi bác sĩ (hiện "Đang cấp số thứ tự…" rồi phiếu xuất hiện)', await b.hasText('Phiếu xếp hàng của ca', 20000), (await b.text()).slice(0, 400));
    await b.goto('/reception/queue');
    check('bảng điều phối: ca có số phiếu trong hàng đợi riêng của bác sĩ', await b.hasText(STAFF.vet.fullName) && await b.hasText(owner.pet) && await b.hasText('Số '), (await b.text()).slice(0, 400));

    step('3. Bác sĩ: gọi số, gọi lại, khám, dịch vụ tại phòng, chỉ định cận lâm sàng');
    check('bác sĩ đăng nhập', await b.login(STAFF.vet.phoneNumber, PASSWORD));
    check('thấy ca trong hàng đợi riêng', await b.hasText(owner.pet));
    await b.click('Gọi số tiếp theo');
    check('gọi số: dòng hiện "Đã gọi lúc"', await b.hasText('Đã gọi lúc'), (await b.text()).slice(0, 300));
    await b.click('Gọi lại');
    check('gọi lại một ca (khách vắng / quay lại) không đổi thứ tự', await b.hasText('Đã gọi') && await b.hasText('Gọi lại'), (await b.text()).slice(0, 300));
    await b.click('Bắt đầu khám', 'button');
    check('mở hồ sơ để bắt đầu khám', await b.waitFor('location.pathname.startsWith("/vet/visits/")', 10000));
    await b.fill('Cân nặng', '12.5'); await b.fill('Thân nhiệt', '39.1'); await b.click('Bắt đầu khám', 'button[type=submit]');
    check('bắt đầu khám (giai đoạn 1)', await b.hasText('Sinh hiệu'));
    await b.click('Dịch vụ & cận lâm sàng', '[role=tab]');
    await checkbox('tẩy giun');
    await b.click('Chỉ định dịch vụ tại phòng');
    check('chỉ định dịch vụ tại phòng', await b.hasText('Đã chỉ định dịch vụ tại phòng'), (await b.text()).slice(0, 300));
    await b.clickRow('tẩy giun', 'Đã thực hiện');
    check('ghi nhận đã thực hiện dịch vụ tại phòng', await b.hasText('Đã ghi nhận thực hiện'));
    await checkbox('CBC'); await checkbox('Siêu âm');
    await b.click('Chỉ định và chuyển sang chờ kết quả');
    check('chỉ định CLS, bác sĩ được giải phóng', await b.waitFor('location.pathname === "/vet/today"', 10000), (await b.text()).slice(0, 300));
    check('ca chờ kết quả không còn trong hàng đợi của bác sĩ', await b.waitFor(`![...document.querySelectorAll('main .ui-list li')].some((li) => li.innerText.includes(${JSON.stringify(owner.pet)}))`, 8000), (await b.text()).slice(0, 600));

    step('3b. Thu ngân: thu tiền xét nghiệm + CĐHA → phát phiếu phòng');
    await b.login(STAFF.reception.phoneNumber, PASSWORD);
    await b.goto('/reception/invoices'); await b.click('Xét nghiệm / CĐHA', '[role=tab]');
    check('ca nằm ở mục thu tiền XN / CĐHA', await b.hasText(owner.pet, 15000), (await b.text()).slice(0, 300));
    await b.clickRow(owner.pet, 'Thu tiền XN / CĐHA');
    check('dịch vụ tại phòng (tẩy giun) chưa thu được trước khi chốt ca', await b.hasText('thu khi bác sĩ chốt ca'), (await b.text()).slice(0, 400));
    await b.click('hóa đơn đã chọn', 'button'); await b.click('Đã thu tiền', 'button[type=submit]');
    check('thu XN + CĐHA → phát phiếu phòng xét nghiệm và CĐHA', await b.hasText('Phiếu xếp hàng của ca', 20000) && await b.hasText('Phòng xét nghiệm') && await b.hasText('Phòng CĐHA'), (await b.text()).slice(0, 400));

    step('4. KTV xét nghiệm và KTV CĐHA: gọi số, bắt đầu, lấy mẫu, nhập kết quả');
    check('KTV xét nghiệm đăng nhập', await b.login(STAFF.lab.phoneNumber, PASSWORD));
    check('thấy ca CBC trong hàng đợi lab', await b.hasText(owner.pet));
    await b.click('Gọi số tiếp theo');
    check('lab gọi số', await b.hasText('Đã gọi lúc'));
    await b.click('Bắt đầu', 'button');
    check('lab bắt đầu: giai đoạn lấy mẫu', await b.hasText('Đang lấy mẫu'), (await b.text()).slice(0, 300));
    await b.click('Đã lấy mẫu');
    check('ghi nhận đã lấy mẫu', await b.hasText('Đang phân tích'));
    await b.click('Nhập kết quả', 'a');
    check('mở form nhập kết quả', await b.waitFor('location.pathname.startsWith("/lab/entry/")'));
    for (const [name, value] of [['Bạch cầu', '25'], ['Hồng cầu', '6'], ['Huyết sắc tố', '14'], ['Tiểu cầu', '300']]) await b.fill(`Giá trị ${name}`, value);
    await b.click('Lưu kết quả và hoàn tất', 'button[type=submit]');
    check('lưu kết quả xét nghiệm', await b.waitFor('location.pathname === "/lab/queue"', 10000), (await b.text()).slice(0, 300));
    check('KTV CĐHA đăng nhập', await b.login(STAFF.imaging.phoneNumber, PASSWORD));
    check('thấy ca siêu âm', await b.hasText(owner.pet));
    await b.click('Gọi số tiếp theo'); await b.click('Bắt đầu', 'button');
    await b.click('Nhập kết quả', 'a');
    await b.fill('Mô tả', 'Gan, thận bình thường'); await b.fill('Kết luận', 'Không phát hiện bất thường');
    await b.setFiles('Tệp ảnh', [png]);
    await b.click('Lưu kết quả và hoàn tất', 'button[type=submit]');
    check('lưu kết quả CĐHA kèm ảnh', await b.waitFor('location.pathname === "/imaging/queue"', 15000), (await b.text()).slice(0, 300));

    step('5. Bác sĩ: ca có kết quả lên đầu, giai đoạn 2, kê đơn (cảnh báo tồn), chốt ca');
    await b.login(STAFF.vet.phoneNumber, PASSWORD);
    check('ca có kết quả hiện huy hiệu và đứng đầu hàng đợi', await b.hasText('Có kết quả') && await b.hasText('Kế tiếp'), (await b.text()).slice(0, 300));
    await b.click('Gọi số tiếp theo'); await b.click('Bắt đầu giai đoạn 2');
    check('bắt đầu giai đoạn 2', await b.waitFor('location.pathname.startsWith("/vet/visits/")', 10000));
    check('thấy kết quả xét nghiệm có đánh giá (WBC cao)', await b.hasText('Cao') && await b.hasText('Bạch cầu'));
    await b.click('Kết luận', '[role=tab]'); await b.fill('Chẩn đoán', 'Viêm dạ dày ruột cấp');
    await b.click('Đơn thuốc', '[role=tab]');
    await b.waitFor('document.querySelectorAll("select.ui-input option").length > 2');
    await b.fill('Thuốc', 'Clavamox'); await b.fill('Số lượng', '999'); await b.fill('Liều dùng', '1 viên/lần'); await b.fill('Tần suất', '2 lần/ngày');
    await b.click('Lưu đơn thuốc');
    check('kê vượt tồn kho: vẫn lưu, chỉ cảnh báo', await b.hasText('Cảnh báo tồn kho') && await b.hasText('Đã lưu đơn thuốc'), (await b.text()).slice(0, 400));
    await b.fill('Số lượng', '10'); await b.click('Cập nhật đơn thuốc');
    check('sửa lại số lượng hợp lệ', await b.hasText('Đã lưu đơn thuốc'));
    await b.click('Chốt ca', '[role=tab]');
    await b.click('Chốt ca và chuyển thanh toán'); await b.click('Chốt ca', 'button[type=submit]');
    check('chốt ca, về hàng đợi', await b.waitFor('location.pathname === "/vet/today"', 12000), (await b.text()).slice(0, 300));

    step('6. Lễ tân: thu dịch vụ tại phòng (giảm giá), bán thuốc; khám/XN/CĐHA đã thu trước');
    await b.login(STAFF.reception.phoneNumber, PASSWORD);
    await b.goto('/reception/invoices'); await b.click('Chốt ca', '[role=tab]');
    check('ca chờ thanh toán có trong danh sách thu ngân', await b.hasText(owner.pet, 15000), (await b.text()).slice(0, 300));
    await b.clickRow(owner.pet, 'Thu tiền');
    check('bảng kê có hóa đơn riêng từng dịch vụ; khám, XN, CĐHA đã thu trước', await b.hasText('Hóa đơn dịch vụ') && await b.hasText('tổng phân tích tế bào máu') && await b.hasText('Siêu âm') && await b.hasText('tẩy giun') && await b.hasText('Đã thanh toán'), (await b.text()).slice(0, 500));
    check('phần bán thuốc thấy đơn Clavamox', await b.hasText('Clavamox'));
    check('hóa đơn thuốc NHÁP tự tạo khi bác sĩ chốt ca (không còn nút lập hóa đơn)', await b.hasText('MINV-', 20000), (await b.text()).slice(0, 300));
    await b.click('Thu tiền thuốc', 'button'); await b.click('Đã thu tiền', 'button[type=submit]');
    check('thu tiền thuốc + trừ kho', await b.hasText('Đã thu tiền thuốc và trừ kho'), (await b.text()).slice(0, 300));
    await b.clickRow('tẩy giun', 'Giảm giá'); await b.fill('Số tiền giảm giá', '10000'); await b.click('Lưu', 'button[type=submit]');
    check('giảm giá hóa đơn dịch vụ tại phòng', await b.hasText('Đã cập nhật giảm giá'), (await b.text()).slice(0, 300));
    await b.click('hóa đơn đã chọn', 'button'); await b.click('Đã thu tiền', 'button[type=submit]');
    check('thu hóa đơn cuối (dịch vụ tại phòng): ca hoàn tất cùng giao dịch', await b.hasText('ca khám hoàn tất'), (await b.text()).slice(0, 300));

    step('7. Chủ nuôi: bệnh án + thông báo');
    await b.login(owner.phone, owner.password);
    await b.goto('/portal/medical-history');
    check('bệnh án hiện chẩn đoán, xét nghiệm, đơn thuốc (không giá/trạng thái)', await b.hasText('Viêm dạ dày ruột cấp') && await b.hasText('Clavamox') && await b.hasText('Bạch cầu'), (await b.text()).slice(0, 500));
    check('ảnh siêu âm tải được', await b.waitFor('document.querySelector("img.ui-image") && document.querySelector("img.ui-image").naturalWidth > 0'));
    await b.goto('/portal/notifications');
    check('có thông báo từ phòng khám', await b.waitFor('document.querySelectorAll(".ui-list li").length > 0', 8000));
    check('nhận thông báo gọi số (phòng + mã ca)', await b.hasText('Mời vào', 8000), (await b.text()).slice(0, 400));
    check('nhận thông báo đã có kết quả', await b.hasText('Đã có kết quả cận lâm sàng', 8000));
    check('nhận thông báo thanh toán thuốc và hoàn tất', await b.hasText('Đã thanh toán thuốc', 8000) && await b.hasText('Thanh toán hoàn tất', 8000));

    step('8. Admin: các trang quản trị tải được, báo cáo một nguồn (reporting-service)');
    check('admin đăng nhập', await b.login('admin', 'Admin@123'));
    for (const [route, marker] of [['/admin/dashboard', 'Ca khám hôm nay'], ['/admin/users', 'Tài khoản & nhân sự'], ['/admin/visits', 'Đợt khám'], ['/admin/services', 'Khám lâm sàng tổng quát'], ['/admin/lab-config', 'Bạch cầu'], ['/admin/species', 'Chó'], ['/admin/medicines', 'VAT'], ['/admin/reports', 'Doanh thu thực thu']]) {
      await b.goto(route);
      check(`${route} tải được`, await b.hasText(marker, 15000) && !(await b.text()).includes('Thử lại'), (await b.text()).slice(0, 200));
    }
    check('báo cáo doanh thu một nguồn (reporting-service): tổng thực thu, dịch vụ + thuốc, theo nhóm và theo kỳ', await b.hasText('Doanh thu thực thu') && await b.hasText('Doanh thu theo nhóm') && await b.hasText('Doanh thu theo kỳ') && !(await b.text()).includes('Công nợ hiện tại'), (await b.text()).slice(0, 300));
    await b.click('Vận hành', '[role=tab]');
    check('báo cáo vận hành (tổng hợp ca + hiệu suất Bác sĩ)', await b.hasText('Tổng số ca') && await b.hasText('Hiệu suất theo bác sĩ'), (await b.text()).slice(0, 300));
    await b.goto('/admin/medicines'); await b.clickRow('Clavamox', 'Nhật ký');
    check('nhật ký kho có dòng bán thuốc số âm', await b.hasText('Bán thuốc') && await b.hasText('-10'), (await b.text()).slice(0, 400));

    step('9. Phân quyền phía giao diện');
    await b.login(STAFF.lab.phoneNumber, PASSWORD);
    await b.goto('/admin/users');
    check('KTV vào trang admin bị chặn (403)', await b.hasText('Bạn không có quyền truy cập'));
    await b.reset(); await b.goto('/reception/queue');
    check('chưa đăng nhập bị chuyển về trang đăng nhập', await b.waitFor('location.pathname === "/auth/login"'));
  } finally {
    const unexpected = b.problems.filter((p) => !/HTTP 404 \/api\/v1\/clinical\/pet-visits\/[^/]+\/prescription/.test(p) && !/HTTP 401 \/api\/v1\/auth\/(login|me)/.test(p));
    console.log(`\nLỗi console/mạng phát sinh trong lúc chạy (${unexpected.length}):`); [...new Set(unexpected)].slice(0, 25).forEach((p) => console.log(`  - ${p}`));
    child.kill(); server?.close(); try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* Chrome còn giữ tệp */ }
  }
  console.log(`\n${passed} đạt, ${failures.length} lỗi${failures.length ? `: ${failures.join('; ')}` : ''}`);
  process.exit(failures.length ? 1 : 0);
}

export { launchChrome, STAFF, PASSWORD, sleep };
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch((problem) => { console.error(problem); process.exit(2); });
