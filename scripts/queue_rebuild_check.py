"""Kiểm tra THẬT bước 6.3 (MySQL + RabbitMQ + Redis thật): bền vững hàng đợi - dựng lại phiếu từ MySQL khi Redis mất dữ liệu (nút quản trị, job khởi động,
job đối soát định kỳ), bộ đếm số phiếu không trùng lịch sử, EX-18 đầy đủ (Redis sập lâu hơn thời gian RabbitMQ thử lại thì sự kiện vào DLQ, đối soát bù lại).
  python scripts/queue_rebuild_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar auth-service / clinical-service trong
<module>/target. Tự dựng RabbitMQ + Redis tạm và dọn đi khi xong."""
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


def start_jar(module, extra=()):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"queue-rebuild-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT,
               REDIS_HOST="localhost", REDIS_PORT=REDIS_PORT)
    args = [JAVA, "-Duser.timezone=UTC", "-Dstdout.encoding=UTF-8", "-jar", jar, f"--server.port={PORTS[module]}", "--eureka.client.enabled=false", *extra]
    for name, port in PORTS.items():
        args.append(f"--spring.cloud.discovery.client.simple.instances.{name}[0].uri=http://localhost:{port}")
    proc = subprocess.Popen(args, stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    PROCS.append(proc)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            return proc
        if proc.poll() is not None:
            break
    sys.exit(f"KHÔNG KHỞI ĐỘNG ĐƯỢC {module}; xem log {log}")


def waiting_zset(doctor):   # ZSET hàng đợi Bác sĩ (UNASSIGNED nếu doctor là None)
    return f"queue:{TODAY}:DOCTOR:{doctor or 'UNASSIGNED'}"


FAST = ["--vetclinic.queue.reconcile-initial-delay-ms=3000", "--vetclinic.queue.reconcile-delay-ms=2000", "--vetclinic.queue.reconcile-min-age-seconds=6"]
SLOW = ["--vetclinic.queue.reconcile-initial-delay-ms=3600000"]   # tắt đối soát để kiểm tra riêng nút quản trị
clinical = None


def restart_clinical(extra):
    global clinical
    if clinical is not None:
        clinical.terminate()
        clinical.wait(timeout=30)
        PROCS.remove(clinical)
    clinical = start_jar("clinical-service", extra)


try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh clinical-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine", "redis-server", "--appendonly", "yes", "--appendfsync", "always")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    # Dọn dữ liệu của lần chạy trước TRƯỚC khi bật service (job khởi động dựng lại phiếu cho mọi ca còn dở trong MySQL)
    sql("DELETE FROM pet_clinic_clinical_db.queue_ticket_histories")
    sql(f"UPDATE {CDB}.pet_visits SET status = 'CANCELLED' WHERE doctor_id IN ({b(TIEN)}, {b(AN)}) AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY', 'WAITING_RESULTS')")
    restart_clinical(SLOW)
    start_jar("auth-service")

    rec, tien, lan, lab, admin = login("letan_hoa"), login("bs_tien"), login("khach_lan"), login("ktv_lab_minh"), login("admin")
    CBC = uuid_of(sql(f"SELECT HEX(id) FROM {CDB}.services WHERE code = 'LAB_CBC'"))
    XRAY = uuid_of(sql(f"SELECT HEX(id) FROM {CDB}.services WHERE code = 'IMG_XRAY'"))

    def new_visit(label, emergency=False, doctor=TIEN):
        st, res = clin("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"{label}-{SUFFIX}", "gender": "MALE"})
        st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": res["data"]["id"], "doctorId": doctor, "isEmergency": emergency, "reasonForVisit": label})
        assert st in (200, 201), (st, res)
        return res["data"]["id"]

    def statement(vid):
        return clin("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]

    def pay(vid, predicate=lambda i: True):
        ids = [i["id"] for i in statement(vid)["invoices"] if i["paymentStatus"] == "PENDING" and predicate(i)]
        return clin("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})

    def tickets(vid):
        try:
            return statement(vid)["tickets"]
        except Exception:
            return []

    def wait_tickets(vid, n=1, timeout=20):
        return wait_for(lambda: (lambda t: t if len(t) >= n else None)(tickets(vid)), timeout)

    def my_queue():
        st, res = clin("GET", f"{BASE}/my-queue", tien)
        return [v["id"] for v in res["data"]] if st == 200 else None

    def doctor_ticket(vid):
        return next((t for t in tickets(vid) if t["queueType"] == "DOCTOR"), None)

    def paid_exam(label, emergency=False):
        vid = new_visit(label, emergency)
        if emergency:
            wait_tickets(vid)
        else:
            assert pay(vid, lambda i: i["category"] == "EXAMINATION")[0] == 200
            wait_tickets(vid)
        return vid

    # ================================================================== dựng hiện trạng: lịch sử phiếu, ca chờ khám, ca cấp cứu, ca có kết quả, ca chờ kết quả
    g = paid_exam("Hủy-lịch-sử")
    clin("POST", f"{BASE}/{g}/cancel", rec, {"reason": "tạo lịch sử"})   # phiếu DOCTOR CANCELLED -> lịch sử giữ số phiếu
    f = paid_exam("CLS-chờ")
    assert clin("POST", f"{BASE}/{f}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})[0] == 200
    assert clin("POST", f"{BASE}/{f}/paraclinical-orders", tien, {"serviceIds": [CBC, XRAY]})[0] == 200
    assert pay(f, lambda i: i["category"] in ("LABORATORY", "IMAGING"))[0] == 200
    wait_tickets(f, 2)
    e = paid_exam("Có-kết-quả")
    assert clin("POST", f"{BASE}/{e}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})[0] == 200
    assert clin("POST", f"{BASE}/{e}/paraclinical-orders", tien, {"serviceIds": [CBC]})[0] == 200
    assert pay(e, lambda i: i["category"] == "LABORATORY")[0] == 200
    wait_tickets(e)
    e_detail = sql(f"SELECT HEX(vsd.id) FROM {CDB}.visit_service_details vsd JOIN {CDB}.services s ON s.id = vsd.service_id WHERE vsd.visit_id = {b(e)} AND s.code = 'LAB_CBC'")
    assert clin("POST", f"{CLIN}/service-details/{uuid_of(e_detail)}/cancel", tien, {"reason": "Máy hỏng", "reasonType": "CLINIC"})[0] == 200
    wait_for(lambda: doctor_ticket(e))
    a1 = paid_exam("A1")
    time.sleep(1.2)
    a2 = paid_exam("A2")
    bb = new_visit("Chưa-nộp-phí")
    c = paid_exam("Cấp-cứu", emergency=True)

    hist_max = int(sql(f"SELECT IFNULL(MAX(ticket_no), 0) FROM {CDB}.queue_ticket_histories WHERE queue_type = 'DOCTOR' AND service_date = CURDATE()") or 0)
    hist_max = max(hist_max, int(sql(f"SELECT IFNULL(MAX(ticket_no), 0) FROM {CDB}.queue_ticket_histories WHERE queue_type = 'DOCTOR'") or 0))
    before_order = my_queue()
    check("hiện trạng: hàng đợi Bác sĩ Tiến = [cấp cứu, ca có kết quả, A1, A2]; lịch sử đã có số phiếu DOCTOR", before_order == [c, e, a1, a2] and hist_max >= 1, (before_order, hist_max))
    check("hiện trạng: ca chờ kết quả có phiếu Lab + CĐHA; ca chưa nộp phí không có phiếu", len(tickets(f)) == 2 and tickets(bb) == [], (tickets(f), tickets(bb)))
    n_before = {v: doctor_ticket(v)["ticketNo"] for v in (c, e, a1, a2)}

    # ================================================================== Redis bị xóa sạch: hàng đợi trống, MySQL vẫn nguyên
    rcli("flushall")
    check("xóa sạch Redis: hàng đợi trống (nhưng API vẫn trả 200, không lỗi)", my_queue() == [] and tickets(a1) == [] and tickets(f) == [])
    check("MySQL không đổi: các ca vẫn ở trạng thái cũ", sql(f"SELECT COUNT(*) FROM {CDB}.pet_visits WHERE id IN ({b(a1)}, {b(a2)}, {b(c)}) AND status = 'WAITING'") == "3"
          and sql(f"SELECT status FROM {CDB}.pet_visits WHERE id = {b(e)}") == "RESULTS_READY" and sql(f"SELECT status FROM {CDB}.pet_visits WHERE id = {b(f)}") == "WAITING_RESULTS")
    check("mất khóa queue:meta của hôm nay", rcli("exists", f"queue:meta:{TODAY}") == "0")

    # ================================================================== nút quản trị: dựng lại từ MySQL
    st, res = clin("POST", f"{CLIN}/queue-tickets/rebuild", rec)
    check("Lễ tân không dựng lại được hàng đợi (chỉ Admin) -> 403", st == 403, (st, res))
    st, res = clin("POST", f"{CLIN}/queue-tickets/rebuild", admin)
    d = res.get("data", {})
    check("Admin dựng lại -> 200: 4 phiếu Bác sĩ (cấp cứu, có kết quả, A1, A2) + 2 phiếu phòng (Lab, CĐHA)", st == 200 and d.get("ran") and d.get("doctorTickets") == 4 and d.get("roomTickets") == 2, (st, res))
    after_order = my_queue()
    check("thứ tự hàng đợi được dựng lại đúng: cấp cứu (2) -> ca có kết quả (1) -> A1 -> A2 (theo giờ thu phí)", after_order == [c, e, a1, a2], after_order)
    n_after = {v: doctor_ticket(v)["ticketNo"] for v in (c, e, a1, a2)}
    check("số phiếu cấp lại tiếp nối SAU lịch sử (không trùng UNIQUE của queue_ticket_histories)", min(n_after.values()) > hist_max and len(set(n_after.values())) == 4, (n_after, hist_max))
    check("số phiếu theo giờ thu tiền: A1 (thu trước) nhỏ hơn A2", n_after[a1] < n_after[a2], n_after)
    check("ca chưa nộp phí vẫn KHÔNG có phiếu (MySQL: hóa đơn phí khám PENDING)", tickets(bb) == [])
    check("ca chờ kết quả có lại 2 phiếu phòng (Lab + CĐHA)", sorted(t["queueType"] for t in tickets(f)) == ["IMAGING", "LABORATORY"], tickets(f))
    check("khóa queue:meta của hôm nay đã được đặt", rcli("exists", f"queue:meta:{TODAY}") == "1")
    seq_doctor = rcli("get", f"queue:seq:{TODAY}:DOCTOR")
    st, res = clin("POST", f"{CLIN}/queue-tickets/rebuild", admin)
    d2 = res.get("data", {})
    check("dựng lại lần hai là idempotent: không phát thêm phiếu nào, bộ đếm không tăng", st == 200 and d2.get("doctorTickets") == 0 and d2.get("roomTickets") == 0
          and my_queue() == [c, e, a1, a2] and rcli("get", f"queue:seq:{TODAY}:DOCTOR") == seq_doctor, (res, my_queue()))

    # phiếu dựng lại dùng được bình thường và ghi lịch sử không đụng số cũ
    st, res = clin("POST", f"{CLIN}/queue-tickets/{doctor_ticket(a1)['id']}/call", tien)
    check("Bác sĩ gọi phiếu đã dựng lại -> 200", st == 200 and res["data"]["ticketNo"] == n_after[a1], (st, res))
    st, res = clin("POST", f"{BASE}/{a1}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})
    check("bắt đầu khám bằng phiếu đã dựng lại -> 200", st == 200 and res["data"]["status"] == "IN_PROGRESS", (st, res))
    st, res = clin("POST", f"{BASE}/{a1}/cancel-exam", tien, {"reason": "kiểm tra lịch sử"})
    hist_a1 = sql(f"SELECT final_status, ticket_no FROM {CDB}.queue_ticket_histories WHERE visit_id = {b(a1)}")
    check("kết thúc phiếu đã dựng lại: lịch sử ghi bình thường (không đụng UNIQUE số phiếu)", st == 200 and hist_a1 == f"CANCELLED\t{n_after[a1]}", (st, hist_a1))

    # ================================================================== đối soát định kỳ: Redis bị xóa lúc đang chạy -> tự dựng lại (không cần Admin)
    restart_clinical(FAST)
    rcli("flushall")
    restored = wait_for(lambda: my_queue() == [c, e, a2], 25)
    check("Redis bị xóa lúc đang chạy: QueueReconcileJob tự dựng lại hàng đợi (mất meta -> dựng toàn bộ) mà không cần Admin", bool(restored), my_queue())
    check("đối soát: số phiếu vẫn tiếp nối sau lịch sử (không trùng)", all(doctor_ticket(v)["ticketNo"] > hist_max for v in (c, e, a2)), [doctor_ticket(v) for v in (c, e, a2)])

    # ================================================================== job khởi động: Redis trống lúc service khởi động -> dựng lại ngay
    rcli("flushall")
    restart_clinical(FAST)   # ApplicationReadyEvent: mất meta -> dựng lại
    check("khởi động service khi Redis trống: hàng đợi được dựng lại ngay lúc khởi động", wait_for(lambda: my_queue() == [c, e, a2], 15) is not None, my_queue())
    log = open(os.path.join(tempfile.gettempdir(), "queue-rebuild-check-clinical-service.log"), encoding="utf-8", errors="replace").read()
    check("log khởi động ghi việc dựng lại từ MySQL", "hàng đợi hôm nay chưa được dựng" in log or "Dựng lại hàng đợi Redis từ MySQL" in log)

    # ================================================================== EX-18 đầy đủ: Redis sập LÂU hơn thời gian RabbitMQ thử lại -> sự kiện vào DLQ -> đối soát bù
    gg = new_visit("Sập-lâu")
    sh("docker", "stop", REDIS)
    st, res = pay(gg, lambda i: i["category"] == "EXAMINATION")
    check("EX-18: Redis sập mà thu phí khám VẪN thành công", st == 200, (st, res))
    time.sleep(12)   # RabbitMQ thử lại 3 lần (1 + 2 giây) rồi đưa sự kiện vào DLQ; Redis vẫn sập
    sh("docker", "start", REDIS)
    time.sleep(2)
    got = wait_for(lambda: (lambda t: t if t else None)([x for x in tickets(gg) if x["queueType"] == "DOCTOR"]), 40)
    check("Redis bật lại (dữ liệu AOF còn nguyên): đối soát bù phiếu cho khoản đã thu mà sự kiện đã vào DLQ -> ĐÚNG 1 phiếu", bool(got) and len(got) == 1, tickets(gg))
    time.sleep(5)
    check("đối soát chạy tiếp không phát thêm phiếu thứ hai (cùng khóa dedupe)", len([x for x in tickets(gg) if x["queueType"] == "DOCTOR"]) == 1)
    check("ca đã có trong hàng đợi Bác sĩ, thứ tự theo giờ thu phí (sau các ca cũ)", my_queue()[-1] == gg and my_queue()[:3] == [c, e, a2], my_queue())
finally:
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: bền vững hàng đợi (dựng lại từ MySQL, đối soát, khởi động, EX-18 đầy đủ) chạy đúng trên MySQL + RabbitMQ + Redis thật")
