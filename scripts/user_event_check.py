"""Kiểm tra THẬT luồng sự kiện tài khoản (bước 2.3), không cần dựng cả hệ thống Docker:
auth-service (MySQL tạm của service_check.sh) -> outbox -> OutboxRelay -> RabbitMQ tạm -> notification-service -> Lễ tân nhận thông báo.
  python scripts/user_event_check.py
Cần: container MySQL 'vetclinic-check-mysql' đã có (chạy `bash scripts/service_check.sh auth-service` trước, nó tạo + nạp lược đồ),
Docker, JDK 21, jar auth-service + notification-service trong <module>/target (mvn -o -pl auth-service,notification-service install -DskipTests)."""
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
MYSQL = "vetclinic-check-mysql"
RABBIT = "vetclinic-check-rabbit"
RABBIT_PORT = "5673"
AUTH_PORT, NOTI_PORT = 18081, 18085
SUFFIX = str(int(time.time()))[-7:]
FAILS = []
PROCS = []


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8").stdout.strip()


def sql(query):
    return sh("docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "-N", "-B", "-e", query)


def claims(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def call(port, method, path, token=None, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(f"http://localhost:{port}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:  # tự đóng vai Gateway: gắn header danh tính từ claim của JWT
        c = claims(token)
        req.add_header("X-User-Id", c["userId"])
        req.add_header("X-User-Roles", ",".join(c["roles"]))
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {}


def login(username):
    status, res = call(AUTH_PORT, "POST", "/api/v1/auth/login", body={"username": username, "password": "Admin@123"})
    return res["data"]["accessToken"]


def start_jar(module, port, extra_env):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"user-event-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost",
               RABBITMQ_PORT=RABBIT_PORT, **extra_env)
    proc = subprocess.Popen([JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={port}", "--eureka.client.enabled=false",
                             "--spring.cloud.discovery.enabled=false"], stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    PROCS.append(proc)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            return
        if proc.poll() is not None:
            break
    sys.exit(f"KHÔNG KHỞI ĐỘNG ĐƯỢC {module}; xem log {log}")


def wait_for(fn, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.5)
    return None


try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh auth-service' trước")
    sh("docker", "rm", "-f", RABBIT)
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT) or "started" in sh("docker", "logs", "--tail", "5", RABBIT).lower():
            break
    time.sleep(3)
    start_jar("notification-service", NOTI_PORT, {})   # khai báo exchange + queue trước
    start_jar("auth-service", AUTH_PORT, {})

    admin, rec = login("admin"), login("letan_hoa")

    def staff(roles, phone, room=None, vet=False):
        body = {"password": "Staff@1234", "fullName": f"NS {phone}", "phoneNumber": phone, "roleCodes": roles, "roomNumber": room}
        if vet:
            body["vetProfile"] = {"licenseNumber": f"EV-{phone}", "licenseIssuedDate": "2020-01-01", "licenseIssuedBy": "Cục Thú y",
                                  "academicDegree": "Bác sĩ thú y"}
        st, res = call(AUTH_PORT, "POST", "/api/v1/staff", admin, body)
        assert st == 201, (st, res)
        return res["data"]["id"]

    def to_status(user_id, status):
        return [m for m in inbox() if m["data"].get("userId") == user_id and m["data"].get("toStatus") == status]

    def inbox():
        st, res = call(NOTI_PORT, "GET", "/api/v1/notifications?limit=100", rec)
        return [m for m in res.get("data", []) if m.get("type") == "USER_STATUS_CHANGED"] if st == 200 else []

    before = len(inbox())
    vet = staff(["VETERINARIAN"], "096" + SUFFIX, "V" + SUFFIX[-4:], vet=True)
    st, res = call(AUTH_PORT, "POST", f"/api/v1/users/{vet}/lock", admin)
    check("Admin khóa Bác sĩ -> 200", st == 200, (st, res))
    got = wait_for(lambda: to_status(vet, "LOCKED"))
    check("Lễ tân nhận USER_STATUS_CHANGED (khóa) qua outbox + RabbitMQ", bool(got))
    if got:
        check("mức WARNING, nội dung nói phân công lại", got[0]["severity"] == "WARNING" and "phân công lại" in got[0]["body"], got[0])
    pending = wait_for(lambda: sql("SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events WHERE status <> 'PUBLISHED'") == "0")
    check("mọi dòng outbox của auth đều PUBLISHED", bool(pending), sql("SELECT status, COUNT(*) FROM pet_clinic_auth_db.outbox_events GROUP BY status"))

    call(AUTH_PORT, "POST", f"/api/v1/users/{vet}/unlock", admin)
    got = wait_for(lambda: to_status(vet, "ACTIVE"))
    check("kích hoạt lại -> Lễ tân nhận thông tin (INFO)", bool(got) and got[0]["severity"] == "INFO", got)
    call(AUTH_PORT, "POST", f"/api/v1/users/{vet}/deactivate", admin)
    got = wait_for(lambda: to_status(vet, "INACTIVE"))
    check("ngừng hoạt động -> Lễ tân nhận cảnh báo", bool(got) and "ngừng hoạt động" in got[0]["body"], got)

    count = len(inbox())
    other = staff(["RECEPTIONIST"], "095" + SUFFIX)
    call(AUTH_PORT, "POST", f"/api/v1/users/{other}/lock", admin)
    wait_for(lambda: sql("SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events WHERE status <> 'PUBLISHED'") == "0")
    time.sleep(3)
    check("khóa tài khoản KHÔNG phải Bác sĩ -> không báo Lễ tân", len(inbox()) == count, (count, len(inbox())))
    check("sự kiện của tài khoản đó vẫn đi qua outbox", sql(f"SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events WHERE aggregate_id = UNHEX(REPLACE('{other}','-','')) AND event_type = 'UserStatusChangedEvent'") == "1")
finally:
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: UserStatusChangedEvent đi từ auth (outbox) tới notification và báo đúng Lễ tân")
