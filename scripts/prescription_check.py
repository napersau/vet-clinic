"""Kiểm tra THẬT bước 5.2 (MySQL thật + RabbitMQ thật, không cần dựng cả hệ thống Docker): đơn thuốc ở clinical, chốt ca -> PrescriptionCreatedEvent
(outbox) -> billing tạo hóa đơn thuốc NHÁP, sửa / hủy / thu tiền + trừ kho (EX-15, EX-16, EX-19, EX-20), T14 mở lại hồ sơ rồi chốt lại.
  python scripts/prescription_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar của
auth-service / clinical-service / billing-pharmacy-service trong <module>/target (mvn -o -pl ... install -DskipTests).
Trả lại tồn kho của hai thuốc thử ở cuối (kể cả khi lỗi); có thể chạy lại nhiều lần trên cùng CSDL tạm."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from decimal import Decimal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673"
REDIS, REDIS_PORT = "vetclinic-check-redis", "6391"   # hàng đợi trên Redis (bước 6.2): clinical cần Redis để bắt đầu khám (phiếu)
PORTS = {"auth-service": 18081, "clinical-service": 18083, "billing-pharmacy-service": 18084}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien: Bác sĩ phụ trách
AN = "019a9442-d810-7000-8000-000000000003"     # bs_an: Bác sĩ khác
BASE = "/api/v1/clinical/pet-visits"
BILL = "/api/v1/billing/medicine-invoices"
CLIN_DB, BILL_DB = "pet_clinic_clinical_db", "pet_clinic_billing_pharmacy_db"


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args):
    return (subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout or "").strip()


def sql(query):
    return sh("docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "--default-character-set=utf8mb4", "-N", "-B", "-e", query)


def b(uid):
    return f"UNHEX(REPLACE('{uid}','-',''))"


def uuid_of(hex32):
    h = hex32.lower()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def claims(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def call(service, method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(f"http://localhost:{PORTS[service]}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:  # tự đóng vai Gateway: gắn header danh tính từ claim của JWT
        c = claims(token)
        req.add_header("X-User-Id", c["userId"])
        req.add_header("X-User-Roles", ",".join(c["roles"]))
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def clin(method, path, token=None, body=None):
    return call("clinical-service", method, path, token, body)


def bill(method, path, token=None, body=None):
    return call("billing-pharmacy-service", method, path, token, body)


def code(res):
    return res.get("errorCode")


def login(username):
    st, res = call("auth-service", "POST", "/api/v1/auth/login", body={"username": username, "password": "Admin@123"})
    return res["data"]["accessToken"]


def wait_for(fn, seconds=25):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.5)
    return None


def start_jar(module):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"prescription-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT, REDIS_HOST="localhost", REDIS_PORT=REDIS_PORT)
    args = [JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={PORTS[module]}", "--eureka.client.enabled=false"]
    for name, port in PORTS.items():
        args.append(f"--spring.cloud.discovery.client.simple.instances.{name}[0].uri=http://localhost:{port}")
    proc = subprocess.Popen(args, stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    PROCS.append(proc)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            return
        if proc.poll() is not None:
            break
    sys.exit(f"KHÔNG KHỞI ĐỘNG ĐƯỢC {module}; xem log {log}")


def medicine(code_):
    row = sql(f"SELECT HEX(id) FROM {BILL_DB}.medicines WHERE code = '{code_}'")
    return uuid_of(row)


def stock(mid):
    return int(sql(f"SELECT quantity_on_hand FROM {BILL_DB}.medicine_stocks WHERE medicine_id = {b(mid)}"))


def set_stock(mid, qty):
    sql(f"UPDATE {BILL_DB}.medicine_stocks SET quantity_on_hand = {qty} WHERE medicine_id = {b(mid)}")


def price(mid):
    p, v = sql(f"SELECT sale_price, vat_rate FROM {BILL_DB}.medicine_prices WHERE medicine_id = {b(mid)} AND effective_to IS NULL").split("\t")
    return Decimal(p), Decimal(v)


def payable(unit_price, vat, qty):
    return (unit_price * qty * (1 + vat)).quantize(Decimal("0.01"))


saved_stock = {}
AMOX = DRON = None
try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh clinical-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine")
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    for module in ("billing-pharmacy-service", "clinical-service", "auth-service"):   # billing khai báo queue trước khi clinical phát sự kiện
        start_jar(module)

    sql("DELETE FROM pet_clinic_clinical_db.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1: bỏ lịch sử của lần chạy trước (UNIQUE loại + ngày + số)
    sql(f"UPDATE {CLIN_DB}.pet_visits SET status = 'CANCELLED' WHERE doctor_id IN ({b(TIEN)}, {b(AN)}) AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY')")
    rec, tien, an, lan, nam = login("letan_hoa"), login("bs_tien"), login("bs_an"), login("khach_lan"), login("khach_nam")
    AMOX, DRON = medicine("MED_AMOX"), medicine("MED_DRON_DOG")
    saved_stock = {AMOX: stock(AMOX), DRON: stock(DRON)}
    amox_price, amox_vat = price(AMOX)
    ingredient = sql(f"SELECT ingredient_name FROM {BILL_DB}.medicine_ingredients WHERE medicine_id = {b(AMOX)} ORDER BY id LIMIT 1")
    set_stock(AMOX, 200)
    set_stock(DRON, 3)

    def new_visit(label, allergies=None):
        body = {"speciesId": 1, "name": f"{label}-{SUFFIX}", "gender": "MALE"}
        if allergies:
            body["allergies"] = allergies
        st, res = clin("POST", "/api/v1/pets", lan, body)
        pet = res["data"]["id"]
        st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": pet, "doctorId": TIEN, "reasonForVisit": label})
        vid = res["data"]["id"]
        st, res = clin("GET", f"{BASE}/{vid}/service-invoices", rec)
        ids = [i["id"] for i in res["data"]["invoices"] if i["paymentStatus"] == "PENDING"]
        assert clin("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})[0] == 200
        # phiếu phát SAU khi thu tiền (ServiceInvoicePaidEvent -> QueueIssuerListener -> Redis): chờ có thời hạn
        assert wait_for(lambda: clin("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]["tickets"], 30), "phiếu không xuất hiện"
        st, res = clin("POST", f"{BASE}/{vid}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})
        assert st == 200, (st, res)
        return vid

    def rx(medicine_id, qty, dosage="1 viên", freq="2 lần/ngày", days=5):
        return {"medicineId": medicine_id, "quantity": qty, "dosage": dosage, "frequency": freq, "durationDays": days}

    def close(vid):
        return clin("POST", f"{BASE}/{vid}/close-visit", tien, {"finalDiagnosis": "Viêm da"})

    def drafts(vid):
        st, res = bill("GET", f"{BILL}?visitId={vid}", rec)
        return res["data"] if st == 200 else []

    def wait_draft(vid):
        return wait_for(lambda: drafts(vid), 30)

    # ================================================================== A: kê đơn, cảnh báo, phân quyền
    va = new_visit("ĐơnA", allergies=ingredient)
    st, res = clin("PUT", f"{BASE}/{va}/prescription", rec, {"items": [rx(AMOX, 10)]})
    check("Lễ tân không được kê đơn -> 403", st == 403, (st, res))
    st, res = clin("PUT", f"{BASE}/{va}/prescription", an, {"items": [rx(AMOX, 10)]})
    check("Bác sĩ không phụ trách ca kê đơn -> CLIN_1311", st >= 400 and code(res) == "CLIN_1311", (st, res))
    st, res = clin("PUT", f"{BASE}/{va}/prescription", tien, {"items": []})
    check("đơn không có thuốc -> 400", st == 400, (st, res))
    st, res = clin("PUT", f"{BASE}/{va}/prescription", tien, {"items": [rx(AMOX, 10), rx(AMOX, 2)]})
    check("trùng thuốc trong đơn -> 400", st == 400, (st, res))
    st, res = clin("PUT", f"{BASE}/{va}/prescription", tien, {"items": [rx("019a9442-d808-7000-8000-00000000ffff", 1)]})
    check("thuốc không có trong danh mục -> 404 PHARM_1405", st == 404 and code(res) == "PHARM_1405", (st, res))

    st, res = clin("PUT", f"{BASE}/{va}/prescription", tien, {"doctorNotes": "Cho ăn sau khi uống", "items": [rx(AMOX, 10), rx(DRON, 5)]})
    pa = res.get("data", {})
    check("PUT đơn thuốc -> 200, 2 dòng thuốc", st == 200 and len(pa.get("items", [])) == 2, (st, res))
    check("kê Drontal 5 khi kho còn 3 -> vẫn lưu, stockWarnings (EX-13)", len(pa.get("stockWarnings", [])) == 1, pa.get("stockWarnings"))
    check("thuốc trùng tiền sử dị ứng của thú cưng -> allergyWarning, vẫn lưu", pa.get("allergyWarning") and ingredient.lower() in pa["allergyWarning"].lower(), pa.get("allergyWarning"))
    row = sql(f"SELECT medicine_name_snapshot, unit_snapshot, quantity FROM {CLIN_DB}.prescription_items WHERE prescription_id = {b(pa['id'])} AND medicine_id = {b(AMOX)}").split("\t")
    check("DB: prescription_items có bản chụp tên + đơn vị, không cột giá / trạng thái", row == ["Clavamox 250mg", "Viên", "10"], row)
    check("DB: prescriptions.visit_id UNIQUE, 1 đơn cho đợt khám", sql(f"SELECT COUNT(*) FROM {CLIN_DB}.prescriptions WHERE visit_id = {b(va)}") == "1")
    st, res = clin("PUT", f"{BASE}/{va}/prescription", tien, {"doctorNotes": "Cập nhật", "items": [rx(AMOX, 10), rx(DRON, 5)]})
    check("lưu lại đơn (sửa) -> cùng id đơn, 2 dòng, không vi phạm UNIQUE",
          st == 200 and res["data"]["id"] == pa["id"] and len(res["data"]["items"]) == 2 and sql(f"SELECT COUNT(*) FROM {CLIN_DB}.prescriptions WHERE visit_id = {b(va)}") == "1", (st, res))
    check("chưa chốt ca: billing chưa có hóa đơn nào", drafts(va) == [])

    st, res = clin("GET", f"{BASE}/{va}/prescription", lan)
    check("chủ nuôi xem đơn của thú cưng mình -> 200", st == 200 and len(res["data"]["items"]) == 2, (st, res))
    st, res = clin("GET", f"{BASE}/{va}/prescription/print", nam)
    check("chủ nuôi khác xem đơn -> 404", st == 404, (st, res))

    # ================================================================== chốt ca -> sự kiện -> hóa đơn nháp
    st, res = close(va)
    check("chốt ca có đơn thuốc -> WAITING_PAYMENT", st == 200 and res["data"]["status"] == "WAITING_PAYMENT", (st, res))
    check("outbox clinical có PrescriptionCreatedEvent (routing key prescription.created)",
          sql(f"SELECT COUNT(*) FROM {CLIN_DB}.outbox_events WHERE routing_key = 'prescription.created' AND aggregate_id = UNHEX(REPLACE('{va}','-',''))") == "1")
    inv = wait_draft(va)
    check("clinical -> RabbitMQ -> billing: có hóa đơn thuốc NHÁP PENDING", bool(inv) and inv[0]["paymentStatus"] == "PENDING", inv)
    inv = inv[0]
    check("hóa đơn nháp: snapshot khách / thú cưng / Bác sĩ, mã MINV-", inv["customerName"] and inv["petName"] == f"ĐơnA-{SUFFIX}" and inv["doctorName"] and inv["invoiceCode"].startswith("MINV-"), inv)
    check("2 dòng thuốc, quantity = prescribedQuantity = số kê, giá hiện hành",
          len(inv["items"]) == 2 and all(i["quantity"] == i["prescribedQuantity"] for i in inv["items"])
          and Decimal(str(next(i for i in inv["items"] if i["medicineId"] == AMOX)["unitPrice"])) == amox_price, inv["items"])
    check("DB: medicine_invoices.prescription_id = đơn thuốc (khóa ngoại logic)", sql(f"SELECT COUNT(*) FROM {BILL_DB}.medicine_invoices WHERE prescription_id = {b(pa['id'])}") == "1")
    check("nháp không đụng kho", stock(AMOX) == 200 and stock(DRON) == 3)
    st, res = clin("GET", f"{BASE}/{va}/prescription", rec)
    check("GET đơn thuốc kèm hóa đơn nháp (medicineInvoiceId, PENDING)", st == 200 and res["data"]["medicineInvoiceId"] == inv["id"] and res["data"]["medicineInvoiceStatus"] == "PENDING", (st, res.get("data")))

    # ================================================================== EX-19: sự kiện giao lại không tạo hóa đơn thứ hai
    sql(f"UPDATE {CLIN_DB}.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = NOW() WHERE routing_key = 'prescription.created' AND aggregate_id = UNHEX(REPLACE('{va}','-',''))")
    republished = wait_for(lambda: sql(f"SELECT status FROM {CLIN_DB}.outbox_events WHERE routing_key = 'prescription.created' AND aggregate_id = UNHEX(REPLACE('{va}','-',''))") == "PUBLISHED", 30)
    time.sleep(3)
    check("EX-19: relay gửi lại sự kiện", bool(republished))
    check("EX-19: vẫn chỉ 1 hóa đơn thuốc cho đơn, nháp không đổi", sql(f"SELECT COUNT(*) FROM {BILL_DB}.medicine_invoices WHERE prescription_id = {b(pa['id'])}") == "1" and len(drafts(va)) == 1)
    check("EX-19: processed_events có đúng 1 dòng cho billing.prescription-created của sự kiện này",
          sql(f"SELECT COUNT(*) FROM {BILL_DB}.processed_events WHERE consumer = 'billing.prescription-created' AND event_id = (SELECT event_id FROM {CLIN_DB}.outbox_events WHERE routing_key = 'prescription.created' AND aggregate_id = UNHEX(REPLACE('{va}','-','')))") == "1")

    # ================================================================== Thu ngân sửa nháp, EX-15 thiếu kho
    iid = inv["id"]
    check("Bác sĩ không sửa được hóa đơn thuốc -> 403", bill("PUT", f"{BILL}/{iid}/items", tien, {"items": [{"medicineId": AMOX, "quantity": 5}]})[0] == 403)
    st, res = bill("PUT", f"{BILL}/{iid}/items", rec, {"items": [{"medicineId": AMOX, "quantity": 201}]})
    check("sửa số lượng vượt tồn kho -> PHARM_1406", st >= 400 and code(res) == "PHARM_1406", (st, res))
    st, res = bill("PUT", f"{BILL}/{iid}/items", rec, {"items": [{"medicineId": AMOX, "quantity": 12}]})
    check("khách mua nhiều hơn số kê (12 > 10) -> được, giữ nguyên prescribedQuantity",
          st == 200 and next(i for i in res["data"]["items"] if i["medicineId"] == AMOX)["quantity"] == 12
          and next(i for i in res["data"]["items"] if i["medicineId"] == AMOX)["prescribedQuantity"] == 10, (st, res))
    st, res = bill("POST", f"{BILL}/{iid}/confirm-payment", rec)
    check("EX-15: thu tiền khi Drontal chỉ còn 3 < cần 5 -> PHARM_1406", st >= 400 and code(res) == "PHARM_1406", (st, res))
    check("EX-15: rollback toàn bộ: hóa đơn vẫn PENDING, kho Clavamox KHÔNG bị trừ, không có nhật ký bán",
          drafts(va)[0]["paymentStatus"] == "PENDING" and stock(AMOX) == 200 and stock(DRON) == 3
          and sql(f"SELECT COUNT(*) FROM {BILL_DB}.inventory_transactions WHERE medicine_invoice_id = {b(iid)}") == "0")
    st, res = bill("PUT", f"{BILL}/{iid}/items", rec, {"items": [{"medicineId": DRON, "quantity": 0}, {"medicineId": AMOX, "quantity": 10}]})
    check("EX-15: bỏ dòng Drontal (quantity 0), còn Clavamox 10 -> 200, 1 dòng", st == 200 and len(res["data"]["items"]) == 1, (st, res))
    st, res = bill("PUT", f"{BILL}/{iid}/items", rec, {"items": [{"medicineId": AMOX, "quantity": 0}]})
    check("bỏ nốt dòng cuối -> 400", st == 400, (st, res))
    st, res = bill("PUT", f"{BILL}/{iid}/items", rec, {"items": [{"medicineId": AMOX, "quantity": 10}], "discountAmount": 100000000})
    check("giảm giá vượt tổng tiền -> BILL_1402", st >= 400 and code(res) == "BILL_1402", (st, res))

    # EX-20: bảng giá đổi sau khi lập nháp -> thu tiền theo giá hiện hành
    new_price = amox_price + Decimal("1000")
    sql(f"UPDATE {BILL_DB}.medicine_prices SET effective_to = UTC_TIMESTAMP() - INTERVAL 1 SECOND WHERE medicine_id = {b(AMOX)} AND effective_to IS NULL")
    sql(f"INSERT INTO {BILL_DB}.medicine_prices (medicine_id, sale_price, vat_rate, effective_from, change_reason, created_by) "
        f"SELECT {b(AMOX)}, {new_price}, {amox_vat}, UTC_TIMESTAMP() - INTERVAL 1 SECOND, 'check EX-20', created_by FROM {BILL_DB}.medicine_prices WHERE medicine_id = {b(AMOX)} LIMIT 1")
    st, res = bill("POST", f"{BILL}/{iid}/confirm-payment", rec)
    d = res.get("data", {})
    check("thu tiền -> 200 PAID", st == 200 and d.get("paymentStatus") == "PAID" and d.get("cashierId"), (st, res))
    check("EX-20: giá được làm mới theo bảng giá hiện hành lúc thu", d and Decimal(str(d["items"][0]["unitPrice"])) == new_price
          and Decimal(str(d["payableAmount"])) == payable(new_price, amox_vat, 10), d)
    check("kho Clavamox 200 -> 190 (một giao dịch với thu tiền)", stock(AMOX) == 190)
    check("nhật ký kho: EXPORT_SALE số âm gắn medicine_invoice_id",
          sql(f"SELECT transaction_type, quantity FROM {BILL_DB}.inventory_transactions WHERE medicine_invoice_id = {b(iid)}") == "EXPORT_SALE\t-10")
    check("outbox billing có MedicineInvoicePaidEvent", sql(f"SELECT COUNT(*) FROM {BILL_DB}.outbox_events WHERE routing_key = 'pharmacy.medicine_invoice_paid' AND aggregate_id = UNHEX(REPLACE('{iid}','-',''))") == "1")
    st, res = bill("POST", f"{BILL}/{iid}/confirm-payment", rec)
    check("EX-16: thu lần hai -> BILL_1403", st == 409 and code(res) == "BILL_1403", (st, res))
    check("EX-16: kho không bị trừ lần hai", stock(AMOX) == 190)
    st, res = bill("PUT", f"{BILL}/{iid}/items", rec, {"items": [{"medicineId": AMOX, "quantity": 1}]})
    check("sửa hóa đơn đã PAID -> BILL_1403", st == 409 and code(res) == "BILL_1403", (st, res))
    st, res = bill("POST", f"{BILL}/{iid}/cancel", rec)
    check("hủy hóa đơn đã PAID -> BILL_1403", st == 409 and code(res) == "BILL_1403", (st, res))

    # ================================================================== T14: mở lại hồ sơ khi hóa đơn đã PAID -> đơn bị khóa (PHARM_1411)
    st, res = clin("POST", f"{BASE}/{va}/reopen", tien, {"reason": "Bổ sung chẩn đoán"})
    check("Bác sĩ mở lại hồ sơ (T14) -> IN_PROGRESS", st == 200 and res["data"]["status"] == "IN_PROGRESS", (st, res))
    st, res = clin("PUT", f"{BASE}/{va}/prescription", tien, {"items": [rx(AMOX, 3)]})
    check("hóa đơn thuốc đã PAID -> không sửa được đơn (PHARM_1411)", st == 409 and code(res) == "PHARM_1411", (st, res))
    check("đơn không đổi sau khi bị từ chối", sql(f"SELECT COUNT(*) FROM {CLIN_DB}.prescription_items WHERE prescription_id = {b(pa['id'])}") == "2")
    check("chốt lại ca", close(va)[0] == 200)
    time.sleep(4)
    again = drafts(va)
    check("chốt lại khi hóa đơn đã PAID: vẫn 1 hóa đơn, PAID, dòng thuốc không đổi (chỉ ghi log PHARM_1407)",
          len(again) == 1 and again[0]["paymentStatus"] == "PAID" and len(again[0]["items"]) == 1 and again[0]["items"][0]["quantity"] == 10, again)
    check("hai sự kiện chốt ca khác nhau của cùng đơn đều được ghi processed_events",
          sql(f"SELECT COUNT(*) FROM {BILL_DB}.processed_events WHERE consumer = 'billing.prescription-created'") != "0")

    # ================================================================== C: chốt, mở lại, sửa đơn, chốt lại -> nháp dựng lại; khách mua ngoài -> hủy
    set_stock(DRON, 100)
    vc = new_visit("ĐơnC")
    pc = clin("PUT", f"{BASE}/{vc}/prescription", tien, {"items": [rx(AMOX, 4), rx(DRON, 2)]})[1]["data"]
    check("chốt ca C", close(vc)[0] == 200)
    ic = (wait_draft(vc) or [{}])[0]
    check("C: nháp 2 dòng (Clavamox 4, Drontal 2)", len(ic.get("items", [])) == 2, ic)
    st, res = bill("PUT", f"{BILL}/{ic['id']}/items", rec, {"items": [{"medicineId": AMOX, "quantity": 1}], "discountAmount": 1000})
    check("C: Thu ngân đã sửa nháp (Clavamox còn 1, giảm giá 1.000)", st == 200 and Decimal(str(res["data"]["discountAmount"])) == 1000, (st, res))
    check("C: Bác sĩ mở lại hồ sơ", clin("POST", f"{BASE}/{vc}/reopen", tien, {"reason": "Đổi thuốc"})[0] == 200)
    vitamin = sql(f"SELECT HEX(id) FROM {BILL_DB}.medicines WHERE code NOT IN ('MED_AMOX', 'MED_DRON_DOG') AND is_active = 1 ORDER BY code LIMIT 1")
    third = uuid_of(vitamin)
    st, res = clin("PUT", f"{BASE}/{vc}/prescription", tien, {"items": [rx(AMOX, 6), rx(third, 2)]})
    check("C: hóa đơn nháp PENDING -> vẫn sửa được đơn (bỏ Drontal, sửa Clavamox 6, thêm thuốc mới)",
          st == 200 and res["data"]["id"] == pc["id"] and len(res["data"]["items"]) == 2, (st, res))
    check("C: chốt lại ca", close(vc)[0] == 200)
    rebuilt = wait_for(lambda: (lambda d: d if d and {i["medicineId"] for i in d[0]["items"]} == {AMOX, third} else None)(drafts(vc)), 30)
    check("C: nháp được DỰNG LẠI theo đơn mới (cùng 1 hóa đơn, Clavamox 6 = số kê, thuốc mới 2, Drontal đã bỏ, giảm giá về 0)",
          bool(rebuilt) and len(rebuilt) == 1 and rebuilt[0]["id"] == ic["id"]
          and next(i for i in rebuilt[0]["items"] if i["medicineId"] == AMOX)["quantity"] == 6 and Decimal(str(rebuilt[0]["discountAmount"])) == 0, rebuilt)
    st, res = bill("POST", f"{BILL}/{ic['id']}/cancel", rec)
    check("C: khách mua ngoài -> hủy hóa đơn nháp: CANCELLED", st == 200 and res["data"]["paymentStatus"] == "CANCELLED", (st, res))
    check("C: hủy không đổi kho", stock(AMOX) == 190 and stock(DRON) == 100)
    check("C: mở lại rồi chốt lại sau khi hủy", clin("POST", f"{BASE}/{vc}/reopen", tien, {"reason": "Kiểm tra"})[0] == 200 and close(vc)[0] == 200)
    time.sleep(4)
    again = drafts(vc)
    check("C: hóa đơn đã hủy là kết thúc: chốt lại không lập hóa đơn mới (vẫn 1, CANCELLED)", len(again) == 1 and again[0]["paymentStatus"] == "CANCELLED", again)
    st, res = bill("POST", f"{BILL}/{ic['id']}/confirm-payment", rec)
    check("thu hóa đơn đã hủy -> BILL_1408", st >= 400 and code(res) == "BILL_1408", (st, res))
    st, res = clin("GET", f"{BASE}/{vc}/prescription/print", lan)
    check("in đơn cho khách mua ngoài: đọc được, không tạo gì", st == 200 and len(res["data"]["items"]) == 2, (st, res))

    # ================================================================== EX-16 đồng thời: hai Thu ngân cùng bấm thu
    vd = new_visit("ĐơnD")
    clin("PUT", f"{BASE}/{vd}/prescription", tien, {"items": [rx(AMOX, 5)]})
    check("chốt ca D", close(vd)[0] == 200)
    idd = (wait_draft(vd) or [{}])[0]
    before = stock(AMOX)
    results = []

    def pay():
        results.append(bill("POST", f"{BILL}/{idd['id']}/confirm-payment", rec))

    threads = [threading.Thread(target=pay) for _ in range(2)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    ok = [r for r in results if r[0] == 200]
    lost = [r for r in results if r[0] != 200]
    check("EX-16: bấm đúp đồng thời -> đúng 1 lần thành công, lần kia 409 (BILL_1403 / SYS_1005)",
          len(ok) == 1 and len(lost) == 1 and lost[0][0] == 409 and code(lost[0][1]) in ("BILL_1403", "SYS_1005"), results)
    check("EX-16: kho chỉ bị trừ một lần (5)", stock(AMOX) == before - 5, (before, stock(AMOX)))
    check("EX-16: chỉ 1 dòng nhật ký bán", sql(f"SELECT COUNT(*) FROM {BILL_DB}.inventory_transactions WHERE medicine_invoice_id = {b(idd['id'])}") == "1")
finally:
    for mid, qty in saved_stock.items():
        set_stock(mid, qty)
    if AMOX:   # trả bảng giá Clavamox về như seed (xóa dòng giá thử, mở lại dòng giá cuối cùng)
        sql(f"DELETE FROM {BILL_DB}.medicine_prices WHERE change_reason = 'check EX-20'")
        sql(f"UPDATE {BILL_DB}.medicine_prices SET effective_to = NULL WHERE medicine_id = {b(AMOX)} "
            f"AND effective_from = (SELECT m FROM (SELECT MAX(effective_from) AS m FROM {BILL_DB}.medicine_prices WHERE medicine_id = {b(AMOX)}) x)")
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: đơn thuốc ở clinical + hóa đơn thuốc nháp từ PrescriptionCreatedEvent (EX-15/16/19/20, T14) chạy đúng trên MySQL + RabbitMQ thật")
