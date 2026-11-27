"""Kiểm tra THẬT bước 6.2 (MySQL + RabbitMQ + Redis thật, không cần dựng cả hệ thống Docker): hàng đợi chuyển sang Redis, phiếu phát từ sự kiện
ServiceInvoicePaidEvent / VisitCheckedInEvent (cấp cứu) / ParaclinicalResultsReadyEvent, gọi / bắt đầu / chuyển / kết thúc phiếu bằng Lua, lịch sử phiếu
về MySQL, EX-18 (Redis sập thì vẫn thu tiền được), EX-19 (gửi lại sự kiện không phát phiếu thứ hai).
  python scripts/queue_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar của auth-service / clinical-service
trong <module>/target (mvn -o -pl ... install -DskipTests). Tự dựng RabbitMQ + Redis tạm và dọn đi khi xong."""
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
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT, REDIS, REDIS_PORT = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673", "vetclinic-check-redis", "6391"
PORTS = {"auth-service": 18081, "clinical-service": 18083}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien
AN = "019a9442-d810-7000-8000-000000000003"     # bs_an
BASE = "/api/v1/clinical/pet-visits"
CLIN = "/api/v1/clinical"
CDB = "pet_clinic_clinical_db"
TODAY = datetime.now(timezone(timedelta(hours=7))).strftime("%Y%m%d")   # ngày phòng khám (Asia/Ho_Chi_Minh)


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args):
    return (subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout or "").strip()


def sql(query):
    return sh("docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "--default-character-set=utf8mb4", "-N", "-B", "-e", query)


def rcli(*args):
    return sh("docker", "exec", REDIS, "redis-cli", *args)


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


def code(res):
    return res.get("errorCode")


def login(username):
    st, res = call("auth-service", "POST", "/api/v1/auth/login", body={"username": username, "password": "Admin@123"})
    return res["data"]["accessToken"]


def wait_for(fn, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.4)
    return None


def start_jar(module):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"queue-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT,
               REDIS_HOST="localhost", REDIS_PORT=REDIS_PORT)
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


def waiting_zset(doctor):   # ZSET hàng đợi Bác sĩ (UNASSIGNED nếu doctor là None)
    return f"queue:{TODAY}:DOCTOR:{doctor or 'UNASSIGNED'}"


try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh clinical-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine", "redis-server", "--appendonly", "yes", "--appendfsync", "everysec")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    # dọn dữ liệu của lần chạy trước TRƯỚC khi bật service (job khởi động dựng lại phiếu cho mọi ca còn dở trong MySQL)
    sql("DELETE FROM pet_clinic_clinical_db.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1: bỏ lịch sử của lần chạy trước (UNIQUE loại + ngày + số)
    # chạy lại được: đóng các ca còn dở của hai Bác sĩ thử
    sql(f"UPDATE {CDB}.pet_visits SET status = 'CANCELLED' WHERE doctor_id IN ({b(TIEN)}, {b(AN)}) AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY', 'WAITING_RESULTS')")
    for module in ("clinical-service", "auth-service"):
        start_jar(module)

    rec, tien, an, lan, lab = login("letan_hoa"), login("bs_tien"), login("bs_an"), login("khach_lan"), login("ktv_lab_minh")
    CBC = uuid_of(sql(f"SELECT HEX(id) FROM {CDB}.services WHERE code = 'LAB_CBC'"))
    XRAY = uuid_of(sql(f"SELECT HEX(id) FROM {CDB}.services WHERE code = 'IMG_XRAY'"))

    def new_visit(label, emergency=False, doctor=TIEN):
        st, res = clin("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"{label}-{SUFFIX}", "gender": "MALE"})
        st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": res["data"]["id"], "doctorId": doctor, "isEmergency": emergency, "reasonForVisit": label})
        assert st == 201 or st == 200, (st, res)
        return res["data"]["id"]

    def statement(vid):
        return clin("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]

    def pay(vid, token=None, predicate=lambda i: True):
        ids = [i["id"] for i in statement(vid)["invoices"] if i["paymentStatus"] == "PENDING" and predicate(i)]
        return clin("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", token or rec, {"invoiceIds": ids})

    def pay_exam(vid, token=None):
        return pay(vid, token, lambda i: i["category"] == "EXAMINATION")

    def tickets(vid):
        try:
            return statement(vid)["tickets"]
        except Exception:
            return []

    def wait_tickets(vid, n=1, timeout=20):
        return wait_for(lambda: (lambda t: t if len(t) >= n else None)(tickets(vid)), timeout)

    def board():
        return clin("GET", f"{BASE}/queue", rec)

    def ticket_hash(tid):
        raw = rcli("hgetall", f"ticket:{tid}").splitlines()
        return dict(zip(raw[0::2], raw[1::2]))

    def history(vid):
        rows = sql(f"SELECT queue_type, final_status, ticket_no, priority FROM {CDB}.queue_ticket_histories WHERE visit_id = {b(vid)} ORDER BY id")
        return [r.split("\t") for r in rows.splitlines() if r]

    # ================================================================== A: thu phí khám -> phiếu DOCTOR (qua sự kiện), gọi / chuyển / bắt đầu
    v1 = new_visit("Thường1")
    check("tiếp đón ca thường: CHƯA có phiếu (chưa nộp phí khám)", tickets(v1) == [] and rcli("zcard", waiting_zset(TIEN)) == "0")
    st, res = board()
    check("bảng điều phối: ca nằm ở mục \"chờ nộp phí khám\" (đọc từ MySQL)", st == 200 and v1 in [v["id"] for v in res["data"]["awaitingPayment"]], (st, res))
    st, res = pay_exam(v1)
    check("thu phí khám -> 200 (giao dịch chỉ ghi hóa đơn + outbox)", st == 200, (st, res))
    t1 = wait_tickets(v1)
    check("ServiceInvoicePaidEvent -> QueueIssuerListener phát phiếu DOCTOR, WAITING, priority 0", bool(t1) and t1[0]["queueType"] == "DOCTOR" and t1[0]["priority"] == 0 and t1[0]["status"] == "WAITING", t1)
    tid1 = t1[0]["id"] if t1 else None
    check("Redis: phiếu nằm trong Sorted Set của Bác sĩ Tiến, Hash có số phiếu 1", rcli("zcard", waiting_zset(TIEN)) == "1" and ticket_hash(tid1).get("ticketNo") == "1" and ticket_hash(tid1).get("visitId") == v1)
    check("MySQL không còn chứa phiếu đang chạy (chỉ lịch sử phiếu đã kết thúc)", sql(f"SELECT COUNT(*) FROM {CDB}.queue_ticket_histories WHERE visit_id = {b(v1)}") == "0")
    st, res = board()
    doc = next((d for d in res["data"]["doctors"] if d["doctorId"] == TIEN), {})
    check("bảng điều phối: hàng đợi của Bác sĩ Tiến có 1 phiếu (waitingCount = ZCARD), ca rời mục chờ nộp phí",
          doc.get("waitingCount") == 1 and doc["visits"][0]["id"] == v1 and doc["visits"][0]["ticket"]["ticketNo"] == 1
          and v1 not in [v["id"] for v in res["data"]["awaitingPayment"]], res["data"])
    st, res = clin("GET", f"{BASE}/my-queue", tien)
    check("my-queue của Bác sĩ Tiến trả phiếu của mình", st == 200 and [v["id"] for v in res["data"]] == [v1], (st, res))

    st, res = clin("POST", f"{CLIN}/queue-tickets/{tid1}/call", tien)
    check("Gọi phiếu -> 200", st == 200 and res["data"]["ticketNo"] == 1 and res["data"]["status"] == "WAITING", (st, res))
    clin("POST", f"{CLIN}/queue-tickets/{tid1}/call", tien)
    h = ticket_hash(tid1)
    check("Gọi / Gọi lại chỉ tăng calledCount (2) + lastCalledAt; vẫn WAITING, vẫn trong hàng đợi", h.get("calledCount") == "2" and "lastCalledAt" in h and h.get("status") == "WAITING" and rcli("zcard", waiting_zset(TIEN)) == "1", h)
    st, res = clin("POST", f"{CLIN}/queue-tickets/{tid1}/call", an)
    check("Bác sĩ khác gọi phiếu của Bác sĩ Tiến -> CLIN_1311", st == 403 and code(res) == "CLIN_1311", (st, res))

    st, res = clin("POST", f"{BASE}/{v1}/assign-doctor", rec, {"doctorId": AN})
    check("Lễ tân chuyển ca sang Bác sĩ An -> 200", st == 200, (st, res))
    moved = wait_for(lambda: rcli("zcard", waiting_zset(AN)) == "1" and rcli("zcard", waiting_zset(TIEN)) == "0")
    check("move_ticket.lua: phiếu sang hàng đợi Bác sĩ An, GIỮ số phiếu 1", bool(moved) and ticket_hash(tid1).get("ticketNo") == "1" and ticket_hash(tid1).get("doctorId") == AN)
    check("gỡ phân công -> phiếu về danh sách chờ phân công (UNASSIGNED)", clin("POST", f"{BASE}/{v1}/unassign-doctor", rec)[0] == 200
          and bool(wait_for(lambda: rcli("zcard", waiting_zset(None)) == "1")))
    check("phân công lại Bác sĩ Tiến", clin("POST", f"{BASE}/{v1}/assign-doctor", rec, {"doctorId": TIEN})[0] == 200
          and bool(wait_for(lambda: rcli("zcard", waiting_zset(TIEN)) == "1" and rcli("zcard", waiting_zset(None)) == "0")))

    st, res = clin("POST", f"{BASE}/{v1}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})
    check("Bắt đầu khám (có phiếu) -> 200 IN_PROGRESS", st == 200 and res["data"]["status"] == "IN_PROGRESS", (st, res))
    started = wait_for(lambda: ticket_hash(tid1).get("status") == "IN_SERVICE")
    check("start_ticket.lua sau commit: phiếu IN_SERVICE, rời hàng đợi chờ, Bác sĩ có khóa busy",
          bool(started) and rcli("zcard", waiting_zset(TIEN)) == "0" and rcli("exists", f"queue:busy:{TIEN}") == "1")

    # EX-19 + thu đồng thời: ca thứ hai
    v2 = new_visit("Thường2")
    results = []

    def pay2():
        results.append(pay_exam(v2))

    threads = [threading.Thread(target=pay2) for _ in range(2)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    ok = [r for r in results if r[0] == 200]
    lost = [r for r in results if r[0] != 200]
    check("hai thu ngân thu CÙNG hóa đơn đồng thời: đúng 1 thành công, người kia 409 (BILL_1403 / SYS_1005)", len(ok) == 1 and len(lost) == 1 and lost[0][0] == 409, results)
    t2 = wait_tickets(v2)
    check("chỉ phát ĐÚNG 1 phiếu cho ca 2, số phiếu 2 (không tranh số)", bool(t2) and len(t2) == 1 and t2[0]["ticketNo"] == 2, t2)
    tid2 = t2[0]["id"] if t2 else None
    seq_before = rcli("get", f"queue:seq:{TODAY}:DOCTOR")
    sql(f"UPDATE {CDB}.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE routing_key = 'invoice.service_paid' AND aggregate_id = UNHEX(REPLACE('{v2}','-',''))")
    wait_for(lambda: sql(f"SELECT status FROM {CDB}.outbox_events WHERE routing_key = 'invoice.service_paid' AND aggregate_id = UNHEX(REPLACE('{v2}','-','')) ORDER BY id DESC LIMIT 1") == "PUBLISHED")
    time.sleep(3)
    check("EX-19: sự kiện giao lại KHÔNG phát phiếu thứ hai, số phiếu không tăng (dedupe)", len(tickets(v2)) == 1 and rcli("get", f"queue:seq:{TODAY}:DOCTOR") == seq_before, (tickets(v2), seq_before))
    st, res = clin("POST", f"{CLIN}/queue-tickets/{tid2}/call", tien)
    check("Bác sĩ đang khám dở (ca 1 IN_PROGRESS) chưa gọi thêm được -> CLIN_1302", st == 409 and code(res) == "CLIN_1302", (st, res))

    # ================================================================== B: cấp cứu có phiếu ngay (priority 2), đứng đầu
    v3 = new_visit("Cấp cứu", emergency=True)
    t3 = wait_tickets(v3)
    check("ca cấp cứu: phiếu priority 2 phát NGAY từ VisitCheckedInEvent, không cần thu phí", bool(t3) and t3[0]["priority"] == 2 and t3[0]["queueType"] == "DOCTOR", t3)
    st, res = clin("GET", f"{BASE}/my-queue", tien)
    order = [v["id"] for v in res["data"]]
    check("my-queue: ca cấp cứu (score nhỏ nhất) đứng TRƯỚC ca thường dù đến sau", order == [v3, v2], order)

    # ================================================================== C: chỉ định CLS -> phiếu DOCTOR DONE (lịch sử), thu tiền -> phiếu phòng gộp theo phòng
    st, res = clin("POST", f"{BASE}/{v1}/paraclinical-orders", tien, {"serviceIds": [CBC, XRAY], "notes": "kiểm tra"})
    check("chỉ định xét nghiệm + X-quang -> 200 WAITING_RESULTS", st == 200 and res["data"]["status"] == "WAITING_RESULTS", (st, res))
    hist = history(v1)
    check("phiếu DOCTOR kết thúc: lịch sử ghi trong giao dịch (DONE, số phiếu 1)", hist == [["DOCTOR", "DONE", "1", "0"]], hist)
    check("sau commit finish_ticket.lua: Hash phiếu + khóa busy đã xóa", bool(wait_for(lambda: rcli("exists", f"ticket:{tid1}") == "0" and rcli("exists", f"queue:busy:{TIEN}") == "0")))
    check("chưa nộp tiền CLS -> chưa có phiếu phòng", tickets(v1) == [])
    st, res = pay(v1, rec, lambda i: i["category"] in ("LABORATORY", "IMAGING"))
    check("thu tiền XN + CĐHA trong một lần -> 200", st == 200, (st, res))
    t1b = wait_tickets(v1, 2)
    types = sorted(t["queueType"] for t in (t1b or []))
    check("phát 2 phiếu phòng: LABORATORY và IMAGING (mỗi phòng một phiếu), số phiếu 1", types == ["IMAGING", "LABORATORY"] and all(t["ticketNo"] == 1 for t in t1b), t1b)
    lab_ticket = next(t for t in t1b if t["queueType"] == "LABORATORY")["id"]
    img_ticket = next(t for t in t1b if t["queueType"] == "IMAGING")["id"]
    st, res = clin("GET", f"{CLIN}/lab-queue", lab)
    row = next((r for r in res["data"] if r["visitId"] == v1), None)
    check("hàng đợi phòng Lab hiện dịch vụ kèm phiếu (ticketId là UUID từ Redis)", st == 200 and row is not None and row["ticketId"] == lab_ticket, (st, row))
    detail_id = row["serviceDetailId"] if row else None

    outcomes = []

    def start_room():
        outcomes.append(clin("POST", f"{CLIN}/queue-tickets/{lab_ticket}/start", lab))

    threads = [threading.Thread(target=start_room) for _ in range(10)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    won = [o for o in outcomes if o[0] == 200]
    codes = sorted(code(o[1]) or "" for o in outcomes if o[0] != 200)
    check("10 thao tác \"Bắt đầu\" đồng thời trên một phiếu phòng: đúng 1 thành công, 9 lần CLIN_1313, KHÔNG có SYS_1005", len(won) == 1 and codes == ["CLIN_1313"] * 9, [(o[0], code(o[1])) for o in outcomes])
    check("phiếu Lab IN_SERVICE trên Redis (start_ticket.lua sau commit)", bool(wait_for(lambda: ticket_hash(lab_ticket).get("status") == "IN_SERVICE")))

    form = clin("GET", f"{CLIN}/service-details/{detail_id}/lab-form", lab)[1]["data"]
    st, res = clin("POST", f"{CLIN}/lab-results/{detail_id}", lab, {"results": [{"labTestItemId": i["id"], "resultValue": 10} for i in form]})
    check("nhập kết quả xét nghiệm -> 200", st == 200, (st, res))
    check("phiếu Lab hoàn tất: lịch sử DONE + xóa khỏi Redis", bool(wait_for(lambda: rcli("exists", f"ticket:{lab_ticket}") == "0")) and ["LABORATORY", "DONE", "1", "0"] in history(v1), history(v1))

    # hoàn tiền X-quang (phòng khám không làm được) -> ServiceInvoiceRefundedEvent + phiếu phòng CĐHA CANCELLED
    img_detail = sql(f"SELECT HEX(vsd.id) FROM {CDB}.visit_service_details vsd JOIN {CDB}.services s ON s.id = vsd.service_id WHERE vsd.visit_id = {b(v1)} AND s.code = 'IMG_XRAY'")
    st, res = clin("POST", f"{CLIN}/service-details/{uuid_of(img_detail)}/cancel", tien, {"reason": "Máy X-quang hỏng", "reasonType": "CLINIC"})
    check("hủy X-quang do phòng khám (đã thu tiền) -> 200", st == 200 and res["data"]["status"] == "CANCELLED", (st, res))
    check("hóa đơn X-quang REFUNDED + outbox có ServiceInvoiceRefundedEvent", sql(f"SELECT COUNT(*) FROM {CDB}.service_invoices WHERE payment_status = 'REFUNDED' AND visit_service_detail_id = {b(uuid_of(img_detail))}") == "1"
          and sql(f"SELECT COUNT(*) FROM {CDB}.outbox_events WHERE routing_key = 'invoice.service_refunded' AND aggregate_id = UNHEX(REPLACE('{v1}','-',''))") == "1")
    check("phiếu CĐHA hết dịch vụ chờ: lịch sử CANCELLED + xóa khỏi Redis", bool(wait_for(lambda: rcli("exists", f"ticket:{img_ticket}") == "0")) and ["IMAGING", "CANCELLED", "1", "0"] in history(v1), history(v1))

    # T06: đủ kết quả -> phiếu ưu tiên 1
    check("đủ kết quả cận lâm sàng -> RESULTS_READY (T06)", sql(f"SELECT status FROM {CDB}.pet_visits WHERE id = {b(v1)}") == "RESULTS_READY")
    pr = wait_for(lambda: (lambda t: t if t and t[0]["priority"] == 1 else None)([x for x in tickets(v1) if x["queueType"] == "DOCTOR"]))
    check("ParaclinicalResultsReadyEvent -> phiếu DOCTOR priority 1 cho Bác sĩ khám chính", bool(pr), tickets(v1))
    st, res = clin("GET", f"{BASE}/my-queue", tien)
    order = [v["id"] for v in res["data"]]
    check("my-queue: cấp cứu (2) -> ca có kết quả (1) -> ca thường (0)", order == [v3, v1, v2], order)
    st, res = clin("POST", f"{BASE}/{v1}/start-phase2", tien)
    check("Bắt đầu Giai đoạn 2 (T07) -> 200", st == 200 and res["data"]["status"] == "IN_PROGRESS", (st, res))
    st, res = clin("POST", f"{BASE}/{v1}/close-visit", tien, {"finalDiagnosis": "Viêm da"})
    check("chốt ca -> WAITING_PAYMENT, phiếu ưu tiên DONE", st == 200 and res["data"]["status"] == "WAITING_PAYMENT" and ["DOCTOR", "DONE", "4", "1"] in history(v1), (st, history(v1)))

    # ================================================================== D: hủy tiếp nhận -> phiếu CANCELLED + VisitCancelledEvent
    st, res = clin("POST", f"{BASE}/{v2}/cancel", rec, {"reason": "Khách bận xin về"})
    check("hủy tiếp nhận ca 2 -> 200", st == 200 and res["data"]["status"] == "CANCELLED", (st, res))
    check("phiếu CANCELLED (kèm lý do) trong lịch sử, xóa khỏi Redis, outbox có VisitCancelledEvent",
          sql(f"SELECT final_status, cancel_reason FROM {CDB}.queue_ticket_histories WHERE visit_id = {b(v2)}") == "CANCELLED\tKhách bận xin về"
          and rcli("exists", f"ticket:{tid2}") == "0" and rcli("zcard", waiting_zset(TIEN)) == "1"   # còn phiếu cấp cứu ca 3
          and sql(f"SELECT COUNT(*) FROM {CDB}.outbox_events WHERE routing_key = 'visit.cancelled' AND aggregate_id = UNHEX(REPLACE('{v2}','-',''))") == "1")

    # ================================================================== E: EX-18 Redis sập ngắn: vẫn thu tiền được, bật lại thì có đúng 1 phiếu
    v4 = new_visit("Redis-sập")
    sh("docker", "stop", REDIS)
    t0 = time.time()
    st, res = board()
    check("Redis sập: bảng điều phối trả CLIN_1318 nhanh (< 5 giây), không treo", st == 503 and code(res) == "CLIN_1318" and time.time() - t0 < 5, (st, res, time.time() - t0))
    st, res = clin("GET", f"{BASE}/{v4}", rec)
    check("Redis sập: hồ sơ ca vẫn đọc được từ MySQL (chỉ thiếu phiếu)", st == 200 and res["data"]["id"] == v4, (st, res))
    st, res = pay_exam(v4)
    check("EX-18: Redis sập mà thu phí khám VẪN thành công (giao dịch chỉ ghi MySQL + outbox)", st == 200, (st, res))
    sh("docker", "start", REDIS)
    t4 = wait_for(lambda: (lambda t: t if len(t) == 1 else None)(tickets(v4)), 40)
    check("Redis bật lại: sự kiện được thử lại và phát ĐÚNG 1 phiếu", bool(t4), tickets(v4))
finally:
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: hàng đợi trên Redis (phát phiếu từ sự kiện, Lua, lịch sử MySQL, EX-18/19) chạy đúng trên MySQL + RabbitMQ + Redis thật")
