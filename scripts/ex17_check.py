"""Kiểm tra THẬT bước 4.4 / EX-17 (MySQL thật + RabbitMQ thật): Bác sĩ bị khóa khi còn ca đang chờ.
  python scripts/ex17_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar của
auth-service / clinical-service trong <module>/target (mvn -o -pl ... install -DskipTests).
Khóa rồi MỞ KHÓA lại bs_an ở cuối (kể cả khi lỗi) để CSDL tạm dùng lại được cho các script khác."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673"
REDIS, REDIS_PORT = "vetclinic-check-redis", "6391"   # hàng đợi trên Redis (bước 6.2)
PORTS = {"auth-service": 18081, "clinical-service": 18083}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien: Bác sĩ còn hoạt động, nhận ca được phân công lại
AN = "019a9442-d810-7000-8000-000000000003"     # bs_an: Bác sĩ bị khóa
BASE = "/api/v1/clinical/pet-visits"


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
    log = os.path.join(tempfile.gettempdir(), f"ex17-check-{module}.log")
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


admin = None
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
    for module in ("clinical-service", "auth-service"):   # clinical khai báo queue trước khi auth phát sự kiện
        start_jar(module)

    sql("DELETE FROM pet_clinic_clinical_db.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1: bỏ lịch sử của lần chạy trước (UNIQUE loại + ngày + số)
    # chạy lại được: mở khóa bs_an nếu lần trước dừng giữa chừng, đóng các ca còn dở của hai Bác sĩ
    sql(f"UPDATE pet_clinic_auth_db.users SET status = 'ACTIVE' WHERE id IN ({b(AN)}, {b(TIEN)})")
    sql(f"UPDATE pet_clinic_clinical_db.pet_visits SET status = 'CANCELLED' WHERE doctor_id IN ({b(TIEN)}, {b(AN)}) AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY')")
    admin, rec, tien, an = login("admin"), login("letan_hoa"), login("bs_tien"), login("bs_an")
    lan = login("khach_lan")
    an_name = sql(f"SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = {b(AN)}")

    def new_visit(label):
        st, res = clin("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"{label}-{SUFFIX}", "gender": "MALE"})
        pet = res["data"]["id"]
        st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": pet, "doctorId": AN, "reasonForVisit": label})
        vid = res["data"]["id"]
        st, res = clin("GET", f"{BASE}/{vid}/service-invoices", rec)
        ids = [i["id"] for i in res["data"]["invoices"] if i["paymentStatus"] == "PENDING"]
        assert clin("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})[0] == 200
        # phiếu phát SAU khi thu tiền (ServiceInvoicePaidEvent -> QueueIssuerListener -> Redis): chờ có thời hạn
        assert wait_for(lambda: clin("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]["tickets"], 30), "phiếu không xuất hiện sau khi thu phí"
        return vid

    def ticket(vid):   # [ticket_no, issued_at, status, doctor (hex in hoa không gạch / 'NULL'), priority] đọc từ Hash phiếu trên Redis
        ids = sh("docker", "exec", REDIS, "redis-cli", "smembers", f"queue:visit:{vid}").splitlines()
        if not ids:
            return ["", "", "", "", ""]
        raw = sh("docker", "exec", REDIS, "redis-cli", "hgetall", f"ticket:{ids[0]}").splitlines()
        h = dict(zip(raw[0::2], raw[1::2]))
        doctor = h.get("doctorId", "")
        return [h.get("ticketNo"), h.get("issuedAt"), h.get("status"), doctor.replace("-", "").upper() if doctor else "NULL", h.get("priority")]

    # ================================================================== dựng hàng đợi của Bác sĩ An: 2 ca chờ + 1 ca đang khám
    v1, v2, v3 = new_visit("Chờ1"), new_visit("Chờ2"), new_visit("Khám")
    t1, t2 = ticket(v1), ticket(v2)
    an_hex = AN.replace("-", "").upper()
    check("hai ca chờ có phiếu DOCTOR WAITING gắn Bác sĩ An", t1[2] == "WAITING" and t1[3] == an_hex and t2[2] == "WAITING" and t2[3] == an_hex, (t1, t2))
    check("ca thứ ba bắt đầu khám (IN_PROGRESS)", clin("POST", f"{BASE}/{v3}/start-exam", an, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})[0] == 200)
    check("ca đã gán Bác sĩ An với tên chụp sẵn", sql(f"SELECT doctor_name_snapshot FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v1)}") == an_name)

    # ================================================================== Admin khóa Bác sĩ An (auth ghi UserStatusChangedEvent vào outbox cùng giao dịch)
    st, res = call("auth-service", "POST", f"/api/v1/users/{AN}/lock", admin)
    check("Admin khóa Bác sĩ An -> LOCKED", st == 200 and res["data"]["status"] == "LOCKED", (st, res))

    # BR-39: phân công mới LUÔN hỏi auth-service ngay lúc ghi, nên bị từ chối ngay cả khi sự kiện chưa tới clinical
    st, res = clin("POST", f"{BASE}/{v1}/assign-doctor", rec, {"doctorId": AN})
    check("phân công cho Bác sĩ đã khóa -> từ chối CLIN_1316 (không dựa vào snapshot / sự kiện)", st >= 400 and res.get("errorCode") == "CLIN_1316", (st, res))
    st, res = clin("POST", f"{BASE}/check-in", rec, {"petId": clin("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"Mới-{SUFFIX}", "gender": "MALE"})[1]["data"]["id"], "doctorId": AN})
    check("tiếp đón chọn Bác sĩ đã khóa -> từ chối CLIN_1316, không tạo đợt khám", st >= 400 and res.get("errorCode") == "CLIN_1316", (st, res))

    # ================================================================== clinical nhận sự kiện: gỡ phân công ca chờ, phiếu về chờ phân công (giữ số + giờ phát)
    released = wait_for(lambda: sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.pet_visits WHERE id IN ({b(v1)}, {b(v2)}) AND doctor_id IS NULL") == "2")
    check("auth -> RabbitMQ -> clinical gỡ phân công 2 ca WAITING của Bác sĩ An", bool(released))
    check("bản chụp tên Bác sĩ của hai ca bị xóa cùng lúc", sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.pet_visits WHERE id IN ({b(v1)}, {b(v2)}) AND doctor_name_snapshot IS NOT NULL") == "0")
    wait_for(lambda: ticket(v1)[3] == "NULL" and ticket(v2)[3] == "NULL")   # move_ticket.lua chạy ngay sau commit của listener
    t1b, t2b = ticket(v1), ticket(v2)
    check("phiếu DOCTOR vẫn WAITING, về danh sách chờ phân công (doctor_id NULL)", t1b[2] == "WAITING" and t1b[3] == "NULL" and t2b[2] == "WAITING" and t2b[3] == "NULL", (t1b, t2b))
    check("GIỮ NGUYÊN số phiếu, giờ phát và mức ưu tiên (thứ tự không đổi)", t1b[0] == t1[0] and t1b[1] == t1[1] and t1b[4] == t1[4] and t2b[0] == t2[0] and t2b[1] == t2[1], (t1, t1b))
    st, res = clin("GET", f"{BASE}/queue", rec)
    unassigned = [v["id"] for v in res["data"]["unassigned"]]
    check("bảng điều phối: hai ca xuất hiện ở danh sách chờ phân công, đúng thứ tự phát phiếu",
          st == 200 and unassigned.index(v1) < unassigned.index(v2) if v1 in unassigned and v2 in unassigned else False, (st, unassigned))
    check("Bác sĩ An không còn trong danh sách bác sĩ đang hoạt động của bảng điều phối", all(d["doctorId"] != AN for d in res["data"]["doctors"]))
    check("ca đang khám (IN_PROGRESS) KHÔNG bị ngắt: vẫn thuộc Bác sĩ An", sql(f"SELECT status, HEX(doctor_id) FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v3)}") == f"IN_PROGRESS\t{an_hex}")
    check("processed_events ghi nhận listener (idempotent)", sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.processed_events WHERE consumer = 'clinical.user-status-changed'") != "0")

    # ================================================================== nhãn "Đã khóa": ca cũ vẫn hiện tên Bác sĩ (snapshot), doctorActive = false
    st, res = clin("GET", f"{BASE}/{v3}", rec)
    check("ca đang khám của Bác sĩ đã khóa: doctorName giữ nguyên + doctorActive = false (nhãn \"Đã khóa\")",
          st == 200 and res["data"]["doctorName"] == an_name and res["data"]["doctorActive"] is False, (st, res["data"].get("doctorName"), res["data"].get("doctorActive")))
    st, res = clin("GET", f"{BASE}/{v1}", rec)
    check("ca vừa được gỡ phân công: không có Bác sĩ, doctorActive = null", st == 200 and res["data"]["doctorId"] is None and res["data"]["doctorActive"] is None, res["data"])

    # ================================================================== Lễ tân phân công lại cho Bác sĩ khác: phiếu đi theo, giữ số
    check("phân công lại ca 1 cho Bác sĩ Tiến -> 200", clin("POST", f"{BASE}/{v1}/assign-doctor", rec, {"doctorId": TIEN})[0] == 200)
    t1c = ticket(v1)
    check("phiếu đi theo Bác sĩ mới, vẫn giữ số + giờ phát", t1c[3] == TIEN.replace("-", "").upper() and t1c[0] == t1[0] and t1c[1] == t1[1], t1c)
    check("snapshot tên Bác sĩ mới được chụp", sql(f"SELECT doctor_name_snapshot FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v1)}") != "")

    # ================================================================== mở khóa: không tự trả ca cũ (Lễ tân quyết), nhưng nhận ca mới được
    st, res = call("auth-service", "POST", f"/api/v1/users/{AN}/unlock", admin)
    check("Admin mở khóa Bác sĩ An -> ACTIVE", st == 200 and res["data"]["status"] == "ACTIVE", (st, res))
    time.sleep(3)
    check("mở khóa KHÔNG tự trả lại ca đã gỡ (ca 2 vẫn chờ phân công)", sql(f"SELECT doctor_id IS NULL FROM pet_clinic_clinical_db.pet_visits WHERE id = {b(v2)}") == "1")
    check("sau khi mở khóa phân công cho Bác sĩ An được lại", clin("POST", f"{BASE}/{v2}/assign-doctor", rec, {"doctorId": AN})[0] == 200)
finally:
    if admin:   # luôn mở khóa để CSDL tạm dùng lại được
        call("auth-service", "POST", f"/api/v1/users/{AN}/unlock", admin)
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: EX-17 (Bác sĩ bị khóa khi còn ca đang chờ) chạy đúng trên MySQL + RabbitMQ thật")
