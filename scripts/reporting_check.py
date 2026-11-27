"""Kiểm tra THẬT bước 7.1 + 7.2 (MySQL thật + RabbitMQ thật + Redis thật, không cần dựng cả hệ thống Docker): reporting-service dựng mô hình đọc
báo cáo (CQRS) từ sự kiện: visit_facts, revenue_facts (SALE / REFUND âm), daily_revenue_summaries (cộng dồn nguyên tử), doctor_daily_performances,
idempotent (giao lại không cộng đôi), API báo cáo (7.2: doanh thu theo ngày / nhóm, hiệu suất Bác sĩ, tổng hợp ca; chỉ ADMIN), đổi tên Bác sĩ / chủ nuôi / thú cưng lan sang báo cáo, sự kiện không xử lý được -> thử lại 3 lần -> DLQ.
  python scripts/reporting_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh reporting-service --reset), Docker, JDK 21 và jar của auth-service /
clinical-service / billing-pharmacy-service / reporting-service trong <module>/target (mvn -o -pl ... install -DskipTests).
Dọn bảng báo cáo ở ĐẦU mỗi lần chạy (chạy lại nhiều lần được); trả lại tên Bác sĩ / chủ nuôi và tồn kho ở cuối (kể cả khi lỗi)."""
import base64
import json
import uuid
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT, RABBIT_UI = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673", "15673"
REDIS, REDIS_PORT = "vetclinic-check-redis", "6391"
PORTS = {"auth-service": 18081, "clinical-service": 18083, "billing-pharmacy-service": 18084, "reporting-service": 18086}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien: Bác sĩ phụ trách mọi ca thử
BASE = "/api/v1/clinical/pet-visits"
CLIN = "/api/v1/clinical"
BILL = "/api/v1/billing/medicine-invoices"
CDB, BDB, RDB = "pet_clinic_clinical_db", "pet_clinic_billing_pharmacy_db", "pet_clinic_reporting_db"
TODAY = datetime.now(timezone(timedelta(hours=7))).strftime("%Y-%m-%d")   # ngày nghiệp vụ theo giờ phòng khám


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


def rabbit_api(method, path, body=None):
    auth = base64.b64encode(b"vetclinic:vetclinic_dev").decode()
    req = urllib.request.Request(f"http://localhost:{RABBIT_UI}/api{path}", data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as res:
        return json.loads(res.read() or b"null")


def start_jar(module):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"reporting-check-{module}.log")
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


def dec(value):
    return Decimal(value) if value not in ("", "NULL", None) else Decimal("0")


saved_stock, restore = {}, []
AMOX = None
try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh reporting-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine")
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-p", f"{RABBIT_UI}:15672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    # bảng báo cáo dọn trước khi chạy: mọi dòng sau đây đều do chính lần chạy này sinh ra
    for table in ("visit_facts", "revenue_facts", "daily_revenue_summaries", "doctor_daily_performances", "processed_events"):
        sql(f"DELETE FROM {RDB}.{table}")
    # outbox còn tồn của các script kiểm tra khác (sự kiện PENDING sẽ đổ vào báo cáo; sự kiện cũ sẽ bị job dựng lại 7.3 phát lại) làm lệch số đếm: xóa sạch
    for db in ("pet_clinic_auth_db", "pet_clinic_appointment_db", CDB, BDB):
        sql(f"DELETE FROM {db}.outbox_events")
    sql(f"DELETE FROM {CDB}.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1 (UNIQUE loại + ngày + số)
    sql(f"UPDATE {CDB}.pet_visits SET status = 'CANCELLED' WHERE doctor_id = {b(TIEN)} AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY')")
    for module in ("reporting-service", "billing-pharmacy-service", "clinical-service", "auth-service"):   # reporting khai báo queue trước khi service khác phát sự kiện
        start_jar(module)

    # ================================================================== hạ tầng: 8 queue + 8 DLQ, mỗi queue 1 consumer
    queues = {q["name"]: q for q in rabbit_api("GET", "/queues")}
    expected = ["visit.checked_in", "visit.completed", "visit.cancelled", "invoice.service_paid", "invoice.service_refunded",
                "pharmacy.medicine_invoice_paid", "user.profile_updated", "pet.updated"]
    check("RabbitMQ: 8 queue reporting-service.<routing-key> bền vững, mỗi queue có 1 consumer",
          all(f"reporting-service.{k}" in queues and queues[f"reporting-service.{k}"]["durable"] and queues[f"reporting-service.{k}"]["consumers"] == 1 for k in expected),
          [k for k in expected if f"reporting-service.{k}" not in queues])
    check("RabbitMQ: mỗi queue có DLQ <queue>.dlq", all(f"reporting-service.{k}.dlq" in queues for k in expected))

    rec, tien, lan, admin = login("letan_hoa"), login("bs_tien"), login("khach_lan"), login("admin")
    lan_id = claims(lan)["userId"]
    XRAY = uuid_of(sql(f"SELECT HEX(id) FROM {CDB}.services WHERE code = 'IMG_XRAY'"))
    AMOX = uuid_of(sql(f"SELECT HEX(id) FROM {BDB}.medicines WHERE code = 'MED_AMOX'"))
    saved_stock = {AMOX: int(sql(f"SELECT quantity_on_hand FROM {BDB}.medicine_stocks WHERE medicine_id = {b(AMOX)}"))}
    sql(f"UPDATE {BDB}.medicine_stocks SET quantity_on_hand = 200 WHERE medicine_id = {b(AMOX)}")
    tien_name = sql(f"SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = {b(TIEN)}")
    lan_name = sql(f"SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = {b(lan_id)}")
    species_code = sql(f"SELECT code FROM {CDB}.species WHERE id = 1")

    pets = {}

    def new_visit(label, emergency=False):
        st, res = clin("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"{label}-{SUFFIX}", "gender": "MALE"})
        pet = res["data"]["id"]
        st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": pet, "doctorId": TIEN, "isEmergency": emergency, "reasonForVisit": label})
        assert st in (200, 201), (st, res)
        pets[res["data"]["id"]] = (pet, f"{label}-{SUFFIX}")
        return res["data"]["id"]

    def statement(vid):
        return clin("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]

    def pay(vid, predicate=lambda i: True):
        ids = [i["id"] for i in statement(vid)["invoices"] if i["paymentStatus"] == "PENDING" and predicate(i)]
        return clin("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})

    def wait_ticket(vid):
        return wait_for(lambda: statement(vid)["tickets"], 30)

    def start_exam(vid):
        assert wait_ticket(vid), "phiếu không xuất hiện"
        st, res = clin("POST", f"{BASE}/{vid}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})
        assert st == 200, (st, res)

    def fact(vid):
        return sql(f"SELECT current_status, is_emergency, pet_name, species_code, owner_name, doctor_name, business_date, completed_at IS NOT NULL "
                   f"FROM {RDB}.visit_facts WHERE visit_id = {b(vid)}").split("\t")

    # ================================================================== V1: ca thường, có đơn thuốc -> thu tiền thuốc -> hoàn tất
    v1 = new_visit("Rpt1")
    row = wait_for(lambda: fact(v1) if sql(f"SELECT COUNT(*) FROM {RDB}.visit_facts WHERE visit_id = {b(v1)}") == "1" else None)
    check("VisitCheckedInEvent -> visit_facts: WAITING, tên thú cưng / loài / chủ nuôi / Bác sĩ, ngày nghiệp vụ hôm nay",
          bool(row) and row[:7] == ["WAITING", "0", pets[v1][1], species_code, lan_name, tien_name, TODAY] and row[7] == "0", row)

    check("thu phí khám -> 200", pay(v1)[0] == 200)
    exam = wait_for(lambda: sql(f"SELECT category_code, entry_type, doctor_name FROM {RDB}.revenue_facts WHERE visit_id = {b(v1)} AND revenue_type = 'SERVICE'"))
    check("ServiceInvoicePaidEvent -> revenue_facts: 1 dòng SERVICE / SALE, nhóm EXAMINATION, gắn tên Bác sĩ", exam == f"EXAMINATION\tSALE\t{tien_name}", exam)
    exam_row = sql(f"SELECT amount_before_vat, vat_amount, discount_amount, net_amount, business_date FROM {RDB}.revenue_facts WHERE visit_id = {b(v1)}").split("\t")
    expected_exam = sql(f"SELECT amount_before_vat, vat_amount, discount_amount, payable_amount FROM {CDB}.v_service_invoice_amounts WHERE visit_id = {b(v1)}").split("\t")
    check("số tiền dòng phí khám khớp VIEW của clinical (trước VAT, VAT, giảm giá, phải trả)", [dec(x) for x in exam_row[:4]] == [dec(x) for x in expected_exam] and exam_row[4] == TODAY, (exam_row, expected_exam))
    cat_name = sql(f"SELECT category_name FROM {RDB}.revenue_facts WHERE visit_id = {b(v1)}")
    check("tên nhóm dịch vụ lấy từ sự kiện (không phải mã nhóm)", cat_name == sql(f"SELECT sc.name FROM {CDB}.service_categories sc WHERE sc.code = 'EXAMINATION'"), cat_name)

    start_exam(v1)
    st, res = clin("PUT", f"{BASE}/{v1}/prescription", tien, {"items": [{"medicineId": AMOX, "quantity": 10, "dosage": "1 viên", "frequency": "2 lần/ngày", "durationDays": 5}]})
    check("Bác sĩ kê đơn Clavamox x10 -> 200", st == 200, (st, res))
    check("chốt ca -> 200", clin("POST", f"{BASE}/{v1}/close-visit", tien, {"finalDiagnosis": "Viêm da"})[0] == 200)
    draft = wait_for(lambda: (bill("GET", f"{BILL}?visitId={v1}", rec)[1].get("data") or [None])[0], 30)
    check("hóa đơn thuốc nháp xuất hiện (PrescriptionCreatedEvent)", bool(draft))
    st, res = bill("POST", f"{BILL}/{draft['id']}/confirm-payment", rec)
    check("thu tiền thuốc + trừ kho -> 200", st == 200, (st, res))
    med = wait_for(lambda: sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts WHERE source_invoice_id = {b(draft['id'])}") == "1")
    check("MedicineInvoicePaidEvent -> revenue_facts: 1 dòng MEDICINE / SALE cho Clavamox", bool(med))
    med_row = sql(f"SELECT revenue_type, entry_type, item_code, quantity, category_code, net_amount FROM {RDB}.revenue_facts WHERE source_invoice_id = {b(draft['id'])}").split("\t")
    payable = sql(f"SELECT payable_amount FROM {BDB}.v_medicine_invoice_amounts WHERE invoice_id = {b(draft['id'])}")
    check("dòng thuốc: số lượng 10, số phải trả khớp VIEW của billing (157.500)", med_row[:4] == ["MEDICINE", "SALE", "MED_AMOX", "10"] and dec(med_row[5]) == dec(payable) == Decimal("157500"), (med_row, payable))
    check("nhóm thuốc đến từ sự kiện (mã nhóm khác rỗng)", med_row[4] not in ("", "UNKNOWN"), med_row)

    check("thu nốt T10 (không còn hóa đơn dịch vụ chờ) -> ca COMPLETED", clin("POST", f"{BASE}/{v1}/service-invoices/confirm-payment", rec, {"invoiceIds": []})[0] == 200)
    done = wait_for(lambda: fact(v1) if fact(v1)[0] == "COMPLETED" else None)
    check("VisitCompletedEvent -> visit_facts COMPLETED, có completed_at", bool(done) and done[7] == "1", done)

    # ================================================================== V2: cấp cứu (thu sau) -> hoàn tất
    v2 = new_visit("Rpt2", emergency=True)
    start_exam(v2)
    check("chốt ca cấp cứu -> 200", clin("POST", f"{BASE}/{v2}/close-visit", tien, {"finalDiagnosis": "Chấn thương"})[0] == 200)
    check("thu phí khám cấp cứu (thu sau) + hoàn tất -> 200", pay(v2)[0] == 200)
    done2 = wait_for(lambda: fact(v2) if fact(v2)[0] == "COMPLETED" else None)
    check("ca cấp cứu: visit_facts COMPLETED, is_emergency = 1", bool(done2) and done2[1] == "1", done2)

    # ================================================================== V3: hủy tiếp nhận
    v3 = new_visit("Rpt3")
    check("Lễ tân hủy tiếp nhận -> 200", clin("POST", f"{BASE}/{v3}/cancel", rec, {"reason": "Khách bỏ về"})[0] == 200)
    cancelled = wait_for(lambda: fact(v3) if len(fact(v3)) > 1 and fact(v3)[0] == "CANCELLED" else None)
    check("VisitCancelledEvent -> visit_facts CANCELLED", bool(cancelled), cancelled)

    # ================================================================== V4: X-quang đã thu rồi hoàn (phòng khám không làm được) -> khách bỏ về
    v4 = new_visit("Rpt4")
    check("thu phí khám V4 -> 200", pay(v4)[0] == 200)
    start_exam(v4)
    st, res = clin("POST", f"{BASE}/{v4}/paraclinical-orders", tien, {"serviceIds": [XRAY], "notes": "kiểm tra"})
    check("chỉ định X-quang -> 200", st == 200, (st, res))
    check("thu tiền X-quang -> 200", pay(v4)[0] == 200)
    xray = wait_for(lambda: sql(f"SELECT entry_type, category_code, net_amount FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND category_code = 'IMAGING'"))
    check("dòng thu X-quang vào revenue_facts (SERVICE / SALE, nhóm IMAGING)", bool(xray) and xray.startswith("SALE\tIMAGING"), xray)
    detail = sql(f"SELECT HEX(vsd.id) FROM {CDB}.visit_service_details vsd JOIN {CDB}.services s ON s.id = vsd.service_id WHERE vsd.visit_id = {b(v4)} AND s.code = 'IMG_XRAY'")
    st, res = clin("POST", f"{CLIN}/service-details/{uuid_of(detail)}/cancel", tien, {"reason": "Máy X-quang hỏng", "reasonType": "CLINIC"})
    check("hủy X-quang do phòng khám (đã thu) -> hoàn tiền, 200", st == 200, (st, res))
    refund = wait_for(lambda: sql(f"SELECT quantity, net_amount, amount_before_vat, business_date FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND entry_type = 'REFUND'"))
    sale = sql(f"SELECT quantity, net_amount, amount_before_vat FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND entry_type = 'SALE' AND category_code = 'IMAGING'").split("\t")
    r = (refund or "\t\t\t").split("\t")
    check("ServiceInvoiceRefundedEvent -> dòng REFUND: số lượng và tiền ÂM đúng bằng dòng thu", bool(refund) and int(r[0]) == -int(sale[0]) and dec(r[1]) == -dec(sale[1]) and dec(r[2]) == -dec(sale[2]) and dec(r[1]) < 0, (refund, sale))
    check("thu - hoàn của X-quang = 0 trong revenue_facts", dec(sql(f"SELECT SUM(net_amount) FROM {RDB}.revenue_facts WHERE visit_id = {b(v4)} AND category_code = 'IMAGING'")) == 0)
    check("khách bỏ về (ca hết chỉ định CLS) -> 200", clin("POST", f"{BASE}/{v4}/abandon", tien)[0] == 200)
    done4 = wait_for(lambda: fact(v4) if fact(v4)[0] == "COMPLETED" else None)
    check("ca bỏ về: visit_facts COMPLETED", bool(done4), done4)

    # ================================================================== bất biến: tổng hợp = tổng các dòng fact; khớp nguồn gốc
    time.sleep(2)
    visits = ",".join(b(v) for v in (v1, v2, v4))
    reported = dec(sql(f"SELECT IFNULL(SUM(net_amount), 0) FROM {RDB}.revenue_facts WHERE revenue_type = 'SERVICE' AND visit_id IN ({visits})"))
    source = dec(sql(f"SELECT IFNULL(SUM(payable_amount), 0) FROM {CDB}.v_service_invoice_amounts WHERE payment_status = 'PAID' AND visit_id IN ({visits})"))
    check("doanh thu dịch vụ của 3 ca trong báo cáo (thu trừ hoàn) = tổng hóa đơn PAID trong clinical", reported == source and reported > 0, (reported, source))
    facts_by_group = sql(f"SELECT business_date, revenue_type, category_code, SUM(net_amount), SUM(vat_amount), SUM(discount_amount), SUM(amount_before_vat), "
                         f"SUM(CASE entry_type WHEN 'SALE' THEN 1 ELSE -1 END) FROM {RDB}.revenue_facts GROUP BY business_date, revenue_type, category_code ORDER BY 1, 2, 3")
    summary_by_group = sql(f"SELECT business_date, revenue_type, category_code, net_amount, vat_amount, discount_amount, amount_before_vat, line_count "
                           f"FROM {RDB}.daily_revenue_summaries ORDER BY 1, 2, 3")
    norm = lambda text: [[x if i < 3 else str(dec(x)) for i, x in enumerate(line.split("\t"))] for line in text.splitlines()]
    check("daily_revenue_summaries khớp GROUP BY của revenue_facts (từng ngày / loại / nhóm: tiền, VAT, giảm giá, số dòng)", bool(summary_by_group) and norm(facts_by_group) == norm(summary_by_group), (facts_by_group, summary_by_group))
    check("tổng hợp có cả nhóm thuốc (MEDICINE) và nhóm dịch vụ (SERVICE) hôm nay",
          sql(f"SELECT COUNT(DISTINCT revenue_type) FROM {RDB}.daily_revenue_summaries WHERE business_date = '{TODAY}'") == "2")
    perf = sql(f"SELECT completed_visits, cancelled_visits, emergency_visits, service_net_revenue, doctor_name FROM {RDB}.doctor_daily_performances WHERE business_date = '{TODAY}' AND doctor_id = {b(TIEN)}").split("\t")
    doc_rev = dec(sql(f"SELECT IFNULL(SUM(net_amount), 0) FROM {RDB}.revenue_facts WHERE revenue_type = 'SERVICE' AND doctor_id = {b(TIEN)} AND business_date = '{TODAY}'"))
    check("hiệu suất Bác sĩ hôm nay: 3 ca hoàn tất (thường, cấp cứu, bỏ về), 1 ca hủy, 1 ca cấp cứu", perf[:3] == ["3", "1", "1"], perf)
    check("hiệu suất Bác sĩ: doanh thu dịch vụ = tổng revenue_facts SERVICE của Bác sĩ (đã trừ hoàn tiền)", dec(perf[3]) == doc_rev and doc_rev > 0, (perf, doc_rev))

    # ================================================================== bước 7.2: API báo cáo (chỉ ADMIN, chỉ đọc reporting_db)
    def rep(path, token=admin):
        return call("reporting-service", "GET", f"/api/v1/reports{path}", token)

    svc_sum = dec(sql(f"SELECT IFNULL(SUM(net_amount), 0) FROM {RDB}.revenue_facts WHERE revenue_type = 'SERVICE'"))
    med_sum = dec(sql(f"SELECT IFNULL(SUM(net_amount), 0) FROM {RDB}.revenue_facts WHERE revenue_type = 'MEDICINE'"))
    st, res = rep(f"/revenue/daily?from={TODAY}&to={TODAY}")
    d = res.get("data") or {}
    check("GET /revenue/daily: 200, tổng dịch vụ / thuốc khớp revenue_facts (đã trừ hoàn tiền), thuốc = 157.500",
          st == 200 and dec(d["totals"]["serviceNetAmount"]) == svc_sum and dec(d["totals"]["medicineNetAmount"]) == med_sum == Decimal("157500")
          and dec(d["totals"]["netAmount"]) == svc_sum + med_sum, (st, d.get("totals"), svc_sum, med_sum))
    check("/revenue/daily: một dòng cho mỗi (ngày, loại) = SERVICE và MEDICINE hôm nay",
          sorted((i["businessDate"], i["revenueType"]) for i in d["items"]) == [(TODAY, "MEDICINE"), (TODAY, "SERVICE")], d.get("items"))
    st, res = rep(f"/revenue/daily?from={TODAY}&to={TODAY}&type=MEDICINE")
    d = res.get("data") or {}
    check("/revenue/daily?type=MEDICINE chỉ trả dòng thuốc", st == 200 and [i["revenueType"] for i in d["items"]] == ["MEDICINE"] and dec(d["totals"]["netAmount"]) == med_sum, (st, d))
    st, res = rep(f"/revenue/by-category?month={TODAY[:7]}")
    d = res.get("data") or {}
    cats = {(i["revenueType"], i["categoryCode"]): i for i in d.get("items", [])}
    check("GET /revenue/by-category?month=: nhóm Khám bệnh có doanh thu, X-quang (thu rồi hoàn) ròng = 0, có nhóm thuốc",
          st == 200 and d["month"] == TODAY[:7] and dec(cats[("SERVICE", "EXAMINATION")]["netAmount"]) > 0 and dec(cats[("SERVICE", "IMAGING")]["netAmount"]) == 0
          and cats[("SERVICE", "IMAGING")]["lineCount"] == 0 and any(k[0] == "MEDICINE" for k in cats) and dec(d["totals"]["netAmount"]) == svc_sum + med_sum, (st, d))
    check("tên nhóm trả về là tên hiển thị (Khám bệnh), không phải mã", cats[("SERVICE", "EXAMINATION")]["categoryName"] == sql(f"SELECT name FROM {CDB}.service_categories WHERE code = 'EXAMINATION'"))
    st, res = rep(f"/doctors/performance?from={TODAY}&to={TODAY}")
    docs = {x["doctorId"]: x for x in (res.get("data") or {}).get("doctors", [])}
    t_row = docs.get(TIEN, {})
    check("GET /doctors/performance: Bác sĩ Tiến 3 ca hoàn tất, 1 hủy, 1 cấp cứu, doanh thu dịch vụ đúng, tên từ bảng phẳng",
          st == 200 and [t_row.get("completedVisits"), t_row.get("cancelledVisits"), t_row.get("emergencyVisits")] == [3, 1, 1] and dec(t_row.get("serviceNetRevenue")) == doc_rev
          and t_row.get("doctorName") == tien_name, (st, t_row))
    st, res = rep(f"/visits/summary?date={TODAY}")
    d = res.get("data") or {}
    check("GET /visits/summary?date=: 4 ca (3 hoàn tất, 1 hủy, 1 cấp cứu); X-quang đã hoàn nên không có ca cận lâm sàng",
          st == 200 and [d["totalVisits"], d["completedVisits"], d["cancelledVisits"], d["emergencyVisits"], d["paraclinicalVisits"], d["routineVisits"]] == [4, 3, 1, 1, 0, 4]
          and d["byStatus"] == {"WAITING": 0, "COMPLETED": 3, "CANCELLED": 1}, (st, d))
    check("/visits/summary: khối lượng Bác sĩ Tiến được giao 4 ca, hoàn tất 3",
          any(x["doctorId"] == TIEN and x["assignedVisits"] == 4 and x["completedVisits"] == 3 for x in d.get("doctors", [])), d.get("doctors"))
    check("khoảng ngày đảo ngược -> 400 SYS_1001", (lambda r: r[0] == 400 and r[1].get("errorCode") == "SYS_1001")(rep(f"/revenue/daily?from={TODAY}&to=2020-01-01")))
    check("khoảng quá 366 ngày -> 400", rep(f"/doctors/performance?from=2024-01-01&to={TODAY}")[0] == 400)
    check("type không hợp lệ -> 400", rep("/revenue/daily?type=FOOD")[0] == 400)
    check("Lễ tân và Bác sĩ không xem được báo cáo -> 403", rep("/revenue/daily", rec)[0] == 403 and rep("/visits/summary", tien)[0] == 403 and rep("/doctors/performance", lan)[0] == 403)
    check("không có danh tính -> 401", rep("/revenue/daily", None)[0] in (401, 403))

    # ================================================================== EX-19: giao lại sự kiện -> không cộng đôi
    totals_before = sql(f"SELECT (SELECT SUM(net_amount) FROM {RDB}.daily_revenue_summaries), (SELECT COUNT(*) FROM {RDB}.revenue_facts), "
                        f"(SELECT SUM(completed_visits) FROM {RDB}.doctor_daily_performances)")
    sql(f"UPDATE {CDB}.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE aggregate_id IN ({b(v1)}, {b(v2)}, {b(v4)}) AND routing_key IN ('invoice.service_paid', 'visit.completed', 'invoice.service_refunded')")
    sql(f"UPDATE {BDB}.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE routing_key = 'pharmacy.medicine_invoice_paid' AND aggregate_id = UNHEX(REPLACE('{draft['id']}','-',''))")
    wait_for(lambda: sql(f"SELECT COUNT(*) FROM {CDB}.outbox_events WHERE status = 'PENDING' AND routing_key IN ('invoice.service_paid', 'visit.completed', 'invoice.service_refunded')") == "0"
             and sql(f"SELECT COUNT(*) FROM {BDB}.outbox_events WHERE status = 'PENDING' AND routing_key = 'pharmacy.medicine_invoice_paid'") == "0", 40)
    time.sleep(4)
    totals_after = sql(f"SELECT (SELECT SUM(net_amount) FROM {RDB}.daily_revenue_summaries), (SELECT COUNT(*) FROM {RDB}.revenue_facts), "
                       f"(SELECT SUM(completed_visits) FROM {RDB}.doctor_daily_performances)")
    check("EX-19: giao lại 4 loại sự kiện thu / hoàn / hoàn tất / thuốc -> tổng hợp, số dòng fact và số ca hoàn tất KHÔNG đổi", totals_before == totals_after and totals_before != "", (totals_before, totals_after))
    dup = sql(f"SELECT COUNT(*) FROM (SELECT event_id, consumer FROM {RDB}.processed_events GROUP BY event_id, consumer HAVING COUNT(*) > 1) x")
    check("processed_events: mỗi (sự kiện, listener) đúng một dòng", dup == "0" and sql(f"SELECT COUNT(DISTINCT consumer) FROM {RDB}.processed_events") == "6", sql(f"SELECT consumer, COUNT(*) FROM {RDB}.processed_events GROUP BY consumer"))

    # ================================================================== bước 7.3: dựng lại báo cáo từ outbox của các service nguồn
    def dump():
        """Dấu vân tay (MD5) của cả bốn bảng báo cáo, không kể khóa tự tăng và cột updated_at."""
        return sql(
            "SET SESSION group_concat_max_len = 100000000; SELECT CONCAT_WS('|', "
            f"(SELECT MD5(IFNULL(GROUP_CONCAT(CONCAT_WS(',', HEX(visit_id), visit_code, business_date, checked_in_at, IFNULL(completed_at, ''), current_status, is_emergency, HEX(pet_id), pet_name, species_code, HEX(owner_id), owner_name, IFNULL(HEX(doctor_id), ''), IFNULL(doctor_name, '')) ORDER BY visit_id), '')) FROM {RDB}.visit_facts), "
            f"(SELECT MD5(IFNULL(GROUP_CONCAT(CONCAT_WS(',', revenue_type, entry_type, HEX(source_invoice_id), invoice_code, HEX(item_id), item_code, item_name, category_code, category_name, HEX(visit_id), IFNULL(HEX(doctor_id), ''), IFNULL(doctor_name, ''), IFNULL(HEX(cashier_id), ''), business_date, occurred_at, quantity, amount_before_vat, vat_amount, discount_amount, net_amount) ORDER BY source_invoice_id, item_id, entry_type), '')) FROM {RDB}.revenue_facts), "
            f"(SELECT MD5(IFNULL(GROUP_CONCAT(CONCAT_WS(',', business_date, revenue_type, category_code, category_name, line_count, amount_before_vat, vat_amount, discount_amount, net_amount) ORDER BY business_date, revenue_type, category_code), '')) FROM {RDB}.daily_revenue_summaries), "
            f"(SELECT MD5(IFNULL(GROUP_CONCAT(CONCAT_WS(',', business_date, HEX(doctor_id), doctor_name, completed_visits, cancelled_visits, emergency_visits, service_net_revenue) ORDER BY business_date, doctor_id), '')) FROM {RDB}.doctor_daily_performances))")

    def rebuild(days=1, token=admin):
        return call("reporting-service", "POST", f"/api/v1/reports/rebuild?days={days}", token)

    # dữ liệu CŨ hơn cửa sổ phải được giữ nguyên: một dòng tổng hợp, một hiệu suất Bác sĩ và một ca của ngày 01/01/2026
    OLD_DAY = "2026-01-01"
    OLD_VISIT = "019a9442-d810-7000-8000-0000000000a1"
    sql(f"INSERT INTO {RDB}.daily_revenue_summaries (business_date, revenue_type, category_code, category_name, line_count, amount_before_vat, vat_amount, discount_amount, net_amount) VALUES ('{OLD_DAY}', 'SERVICE', 'OLD', 'Nhóm cũ', 7, 700000, 0, 0, 700000)")
    sql(f"INSERT INTO {RDB}.doctor_daily_performances (business_date, doctor_id, doctor_name, completed_visits, cancelled_visits, emergency_visits, service_net_revenue) VALUES ('{OLD_DAY}', {b(TIEN)}, 'BS cũ', 9, 0, 0, 900000)")
    sql(f"INSERT INTO {RDB}.visit_facts (visit_id, visit_code, business_date, checked_in_at, current_status, is_emergency, pet_id, pet_name, species_code, owner_id, owner_name, doctor_id, doctor_name) VALUES ({b(OLD_VISIT)}, 'VIS-OLD', '{OLD_DAY}', '2026-01-01 03:00:00', 'WAITING', 0, {b(pets[v1][0])}, 'Cũ', 'CAT', {b(lan_id)}, 'Lan', {b(TIEN)}, '{tien_name}')")
    baseline = dump()
    check("dựng lại: có dữ liệu để so sánh (dấu vân tay 4 bảng khác rỗng)", baseline.count("|") == 3 and len(baseline) > 100, baseline)
    st, res = rebuild(1, rec)
    check("dựng lại: Lễ tân không được chạy -> 403", st == 403, (st, res))
    check("dựng lại: số ngày 0 hoặc 7 (quá 6) -> 400", rebuild(0)[0] == 400 and rebuild(7)[0] == 400)
    st, res = rebuild(1, None)
    check("dựng lại: không danh tính -> 401/403", st in (401, 403))

    # (a) dựng lại khi báo cáo đang ĐÚNG: kết quả giống hệt
    st, res = rebuild(1)
    d = res.get("data") or {}
    check("POST /reports/rebuild?days=1 -> 200, đọc đủ sự kiện của 3 service: 4 tiếp đón, 3 hoàn tất, 1 hủy, 4 thu dịch vụ, 1 hoàn tiền, 1 thu thuốc",
          st == 200 and d.get("fetchedByType") == {"VisitCheckedInEvent": 4, "VisitCompletedEvent": 3, "VisitCancelledEvent": 1, "ServiceInvoicePaidEvent": 4,
                                                  "ServiceInvoiceRefundedEvent": 1, "MedicineInvoicePaidEvent": 1}, (st, res))
    check("dựng lại: áp dụng đủ, không bỏ qua sự kiện nào", d.get("fetched") == d.get("applied") == 14 and d.get("skipped") == 0 and d.get("fromDate") == TODAY, d)
    check("dựng lại khi báo cáo đang đúng -> 4 bảng giống HỆT trước đó (idempotent)", dump() == baseline, (baseline, dump()))
    check("processed_events sau dựng lại: mỗi sự kiện phát lại đúng một dòng", sql(f"SELECT COUNT(*) FROM {RDB}.processed_events") == "14")

    # (b) làm hỏng mô hình đọc rồi dựng lại: phải trở về đúng như cũ
    sql(f"DELETE FROM {RDB}.revenue_facts WHERE business_date = '{TODAY}'")
    sql(f"DELETE FROM {RDB}.daily_revenue_summaries WHERE business_date = '{TODAY}'")
    sql(f"DELETE FROM {RDB}.doctor_daily_performances WHERE business_date = '{TODAY}'")
    sql(f"UPDATE {RDB}.visit_facts SET current_status = 'WAITING', completed_at = NULL, doctor_name = 'HỎNG', pet_name = 'HỎNG' WHERE business_date = '{TODAY}'")
    sql(f"DELETE FROM {RDB}.processed_events")
    check("báo cáo đã bị làm hỏng (doanh thu hôm nay biến mất)", dump() != baseline and rep("/revenue/daily?from=" + TODAY + "&to=" + TODAY)[1]["data"]["totals"]["netAmount"] in (0, "0", 0.0, "0.00"))
    st, res = rebuild(1)
    check("dựng lại báo cáo đã hỏng -> 200", st == 200 and (res.get("data") or {}).get("skipped") == 0, (st, res))
    check("sau dựng lại: 4 bảng trở về GIỐNG HỆT bản gốc (kể cả trạng thái ca, doanh thu, hiệu suất)", dump() == baseline, (baseline, dump()))
    st, res = rep(f"/revenue/daily?from={TODAY}&to={TODAY}")
    check("API doanh thu hôm nay đúng trở lại (khớp VIEW của clinical + billing)", st == 200 and dec(res["data"]["totals"]["serviceNetAmount"]) == svc_sum and dec(res["data"]["totals"]["medicineNetAmount"]) == med_sum, (st, res))
    check("dữ liệu cũ hơn cửa sổ được GIỮ NGUYÊN (dòng tổng hợp, hiệu suất, ca của 01/01/2026)",
          sql(f"SELECT net_amount FROM {RDB}.daily_revenue_summaries WHERE business_date = '{OLD_DAY}' AND category_code = 'OLD'") == "700000.00"
          and sql(f"SELECT completed_visits FROM {RDB}.doctor_daily_performances WHERE business_date = '{OLD_DAY}' AND doctor_id = {b(TIEN)}") == "9"
          and sql(f"SELECT current_status FROM {RDB}.visit_facts WHERE visit_id = {b(OLD_VISIT)}") == "WAITING")
    check("dựng lại KHÔNG gửi lại gì lên RabbitMQ: outbox các service nguồn vẫn không có dòng PENDING / FAILED",
          sql(f"SELECT (SELECT COUNT(*) FROM {CDB}.outbox_events WHERE status <> 'PUBLISHED') + (SELECT COUNT(*) FROM {BDB}.outbox_events WHERE status <> 'PUBLISHED') + (SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events WHERE status <> 'PUBLISHED')") == "0")
    queues_after = {q["name"]: q for q in rabbit_api("GET", "/queues")}
    check("hàng đợi reporting trống sau dựng lại (không phát lại qua RabbitMQ)", all(queues_after[f"reporting-service.{k}"]["messages"] == 0 for k in expected))

    # (c) ca tiếp đón TRƯỚC cửa sổ nhưng hoàn tất TRONG cửa sổ: ca phải được đưa về WAITING rồi đóng lại, hiệu suất Bác sĩ cộng đủ
    perf_before = int(sql(f"SELECT completed_visits FROM {RDB}.doctor_daily_performances WHERE business_date = '{TODAY}' AND doctor_id = {b(TIEN)}"))
    sql(f"UPDATE {RDB}.visit_facts SET current_status = 'COMPLETED', completed_at = '2026-01-01 04:00:00' WHERE visit_id = {b(OLD_VISIT)}")
    now_ms = int(time.time() * 1000)
    raw = now_ms.to_bytes(6, "big") + bytes([0x70, 0x00, 0x80, 0, 0, 0, 0, 0, 0, 0xB2])
    old_event_id = str(uuid.UUID(bytes=raw))
    payload = json.dumps({"eventId": old_event_id, "occurredAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "actorUserId": None, "visitId": OLD_VISIT,
                          "visitCode": "VIS-OLD", "petId": pets[v1][0], "abandoned": False, "doctorId": TIEN, "doctorName": tien_name}, ensure_ascii=False)
    sql(f"INSERT INTO {CDB}.outbox_events (event_id, aggregate_type, aggregate_id, event_type, routing_key, payload, status, published_at) VALUES ({b(old_event_id)}, 'VISIT', {b(OLD_VISIT)}, 'VisitCompletedEvent', 'visit.completed', '{payload.replace(chr(39), chr(39) * 2)}', 'PUBLISHED', NOW())")
    st, res = rebuild(1)
    check("ca cũ đóng trong cửa sổ: dựng lại -> 200, đọc thêm 1 sự kiện hoàn tất, không bỏ qua gì", st == 200 and res["data"]["fetchedByType"]["VisitCompletedEvent"] == 4 and res["data"]["skipped"] == 0, (st, res))
    check("ca cũ được đóng lại đúng (COMPLETED, giờ đóng = giờ sự kiện, vẫn thuộc ngày 01/01/2026)",
          sql(f"SELECT current_status, business_date, completed_at > '2026-11-01' FROM {RDB}.visit_facts WHERE visit_id = {b(OLD_VISIT)}") == f"COMPLETED\t{OLD_DAY}\t1")
    check("hiệu suất Bác sĩ hôm nay cộng thêm đúng 1 ca hoàn tất cho ca cũ (không bị bỏ sót vì ca đã COMPLETED)",
          int(sql(f"SELECT completed_visits FROM {RDB}.doctor_daily_performances WHERE business_date = '{TODAY}' AND doctor_id = {b(TIEN)}")) == perf_before + 1)
    st2, res2 = rebuild(1)
    check("dựng lại lần nữa ngay sau đó cho cùng kết quả (hiệu suất không cộng đôi)",
          st2 == 200 and int(sql(f"SELECT completed_visits FROM {RDB}.doctor_daily_performances WHERE business_date = '{TODAY}' AND doctor_id = {b(TIEN)}")) == perf_before + 1, (st2, res2))

    # (d) một service nguồn không trả lời: dừng NGAY, báo cáo giữ nguyên
    sql(f"DELETE FROM {CDB}.outbox_events WHERE event_id = {b(old_event_id)}")
    sql(f"DELETE FROM {RDB}.visit_facts WHERE visit_id = {b(OLD_VISIT)}")
    sql(f"DELETE FROM {RDB}.daily_revenue_summaries WHERE business_date = '{OLD_DAY}'")
    sql(f"DELETE FROM {RDB}.doctor_daily_performances WHERE business_date = '{OLD_DAY}'")
    sql(f"UPDATE {RDB}.doctor_daily_performances SET completed_visits = completed_visits - 1 WHERE business_date = '{TODAY}' AND doctor_id = {b(TIEN)}")
    stable = dump()
    PROCS[1].terminate()   # billing-pharmacy-service (thứ tự khởi động: reporting, billing, clinical, auth)
    PROCS[1].wait(timeout=30)
    st, res = rebuild(1)
    check("billing-pharmacy-service ngừng chạy: dựng lại -> 503 SYS_1004 nêu rõ service, báo cáo CHƯA bị thay đổi", st == 503 and res.get("errorCode") == "SYS_1004" and "billing-pharmacy-service" in (res.get("message") or "") and dump() == stable, (st, res))
    check("sau lỗi, báo cáo vẫn dùng được (API doanh thu 200)", rep(f"/revenue/daily?from={TODAY}&to={TODAY}")[0] == 200)

    # ================================================================== đổi tên lan sang báo cáo
    new_doc, new_owner, new_pet = f"BS Đổi {SUFFIX}", f"Lan Đổi {SUFFIX}", f"Pet Đổi {SUFFIX}"
    restore.append(lambda: call("auth-service", "PUT", f"/api/v1/users/{TIEN}/profile", admin, {"fullName": tien_name}))
    restore.append(lambda: call("auth-service", "PUT", f"/api/v1/users/{lan_id}/profile", admin, {"fullName": lan_name}))
    check("Admin đổi tên Bác sĩ -> 200", call("auth-service", "PUT", f"/api/v1/users/{TIEN}/profile", admin, {"fullName": new_doc})[0] == 200)
    renamed = wait_for(lambda: sql(f"SELECT COUNT(*) FROM {RDB}.doctor_daily_performances WHERE doctor_id = {b(TIEN)} AND doctor_name = '{new_doc}'") != "0")
    check("UserProfileUpdatedEvent -> tên Bác sĩ mới trên visit_facts, revenue_facts, doctor_daily_performances",
          bool(renamed) and sql(f"SELECT COUNT(*) FROM {RDB}.visit_facts WHERE doctor_id = {b(TIEN)} AND doctor_name <> '{new_doc}'") == "0"
          and sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts WHERE doctor_id = {b(TIEN)} AND doctor_name <> '{new_doc}'") == "0")
    check("Admin đổi tên chủ nuôi -> 200", call("auth-service", "PUT", f"/api/v1/users/{lan_id}/profile", admin, {"fullName": new_owner})[0] == 200)
    check("tên chủ nuôi mới trên visit_facts", bool(wait_for(lambda: sql(f"SELECT COUNT(*) FROM {RDB}.visit_facts WHERE owner_id = {b(lan_id)} AND owner_name <> '{new_owner}'") == "0")))
    pet_id = pets[v1][0]
    check("chủ nuôi đổi tên thú cưng -> 200", clin("PUT", f"/api/v1/pets/{pet_id}", lan, {"name": new_pet})[0] == 200)
    check("PetUpdatedEvent -> pet_name mới trên visit_facts", bool(wait_for(lambda: sql(f"SELECT pet_name FROM {RDB}.visit_facts WHERE visit_id = {b(v1)}") == new_pet)))

    # ================================================================== sự kiện không xử lý được (hoàn tiền không có dòng thu) -> thử lại 3 lần -> DLQ, không ghi gì
    bad = {"eventId": "019a9442-d810-7000-8000-00000000f00d", "occurredAt": "2026-11-21T03:00:00Z", "actorUserId": None, "visitId": v1, "visitCode": "VIS-X",
           "invoiceId": "019a9442-d810-7000-8000-00000000beef", "invoiceCode": "SINV-X", "visitServiceDetailId": None, "serviceId": None, "serviceName": "X",
           "category": "IMAGING", "amount": 1000, "reason": "kiểm tra DLQ"}
    rows_before = sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts")
    rabbit_api("POST", "/exchanges/%2F/clinic.events/publish", {"properties": {"content_type": "application/json"}, "routing_key": "invoice.service_refunded",
                                                              "payload": json.dumps(bad), "payload_encoding": "string"})
    in_dlq = wait_for(lambda: next((q["messages"] for q in rabbit_api("GET", "/queues") if q["name"] == "reporting-service.invoice.service_refunded.dlq"), 0) >= 1, 45)
    check("hoàn tiền không có dòng thu: thử lại 3 lần rồi vào DLQ (queue chính trống)", bool(in_dlq))
    check("sự kiện lỗi KHÔNG để lại dòng fact / processed_events (rollback trọn gói)", sql(f"SELECT COUNT(*) FROM {RDB}.revenue_facts") == rows_before
          and sql(f"SELECT COUNT(*) FROM {RDB}.processed_events WHERE event_id = {b('019a9442-d810-7000-8000-00000000f00d')}") == "0")
finally:
    for undo in restore:
        try:
            undo()
        except Exception:
            pass
    for mid, qty in saved_stock.items():
        sql(f"UPDATE {BDB}.medicine_stocks SET quantity_on_hand = {qty} WHERE medicine_id = {b(mid)}")
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: reporting-service dựng visit_facts / revenue_facts / tổng hợp / hiệu suất Bác sĩ từ 8 loại sự kiện, idempotent, đổi tên lan sang báo cáo, lỗi vào DLQ — chạy đúng trên MySQL + RabbitMQ thật")
