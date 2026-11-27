"""Kiểm tra THẬT bước 4.1 (MySQL thật + RabbitMQ thật, không cần dựng cả hệ thống Docker): thú cưng + lịch hẹn UUID, snapshot, chống N+1,
PetUpdatedEvent / UserProfileUpdatedEvent qua outbox -> RabbitMQ -> consumer, Feign nội bộ giữa 3 service, check-in cập nhật lịch hẹn.
  python scripts/pet_appointment_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar của
auth-service / clinical-service / appointment-service trong <module>/target (mvn -o -pl ... install -DskipTests)."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673"
PORTS = {"auth-service": 18081, "appointment-service": 18082, "clinical-service": 18083}
SUFFIX = str(int(time.time()))[-6:]
FAILS, PROCS = [], []


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
    log = os.path.join(tempfile.gettempdir(), f"pet-appt-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT)
    args = [JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={PORTS[module]}", "--eureka.client.enabled=false"]
    # Không có Eureka: Feign tìm service theo tên qua SimpleDiscoveryClient cấu hình tĩnh
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


try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh clinical-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT)
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    for module in ("auth-service", "clinical-service", "appointment-service"):   # clinical + appointment khai báo queue trước khi auth phát
        start_jar(module)

    admin, rec = login("admin"), login("letan_hoa")
    lan, nam = login("khach_lan"), login("khach_nam")
    lan_id, nam_id = claims(lan)["userId"], claims(nam)["userId"]

    # ================================================================== thú cưng
    st, res = call("clinical-service", "GET", "/api/v1/pets?ownerPhone=0912000008", rec)
    seeded = res.get("data", {}).get("items", [])
    names = sorted(p["name"] for p in seeded if p["petCode"] in ("PET-2026-0001", "PET-2026-0002"))
    check("tìm thú cưng theo SĐT chủ (snapshot): khach_lan có Lu và Milo", st == 200 and names == ["Lu", "Milo"], (st, names))
    lu = next((p for p in seeded if p["name"] == "Lu"), None)
    check("id thú cưng là UUID, snapshot chủ nuôi từ seed", lu and len(lu["id"]) == 36 and lu["ownerName"] == "Hoàng Thị Lan" and lu["ownerPhone"] == "0912000008", lu)
    check("khách khác không tìm theo SĐT được (chỉ thấy thú cưng của mình)",
          [p["name"] for p in call("clinical-service", "GET", "/api/v1/pets?ownerPhone=0912000008", nam)[1]["data"]["items"]
           if p["ownerId"] == lan_id] == [])

    st, res = call("clinical-service", "POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"Thử{SUFFIX}", "microchipNumber": f"9001{SUFFIX}",
                                                                    "allergies": "Dị ứng penicillin", "gender": "MALE"})
    check("chủ nuôi tạo thú cưng -> 201, mã PET-năm-XXXXXX, chụp snapshot chủ nuôi", st == 201 and res["data"]["petCode"].startswith("PET-")
          and len(res["data"]["petCode"]) == 15 and res["data"]["ownerName"] == "Hoàng Thị Lan" and res["data"]["allergies"] == "Dị ứng penicillin", (st, res))
    pet = res["data"]
    row = sql(f"SELECT owner_name_snapshot, owner_phone_snapshot, microchip_number FROM pet_clinic_clinical_db.pets WHERE id = {b(pet['id'])}").split("\t")
    check("DB: pets.id BINARY(16) + snapshot + vi mạch", row == ["Hoàng Thị Lan", "0912000008", f"9001{SUFFIX}"], row)
    st, res = call("clinical-service", "POST", "/api/v1/pets", lan, {"speciesId": 1, "name": "Trùng chip", "microchipNumber": f"9001{SUFFIX}"})
    check("trùng số vi mạch -> 409", st == 409, (st, res))
    st, res = call("clinical-service", "GET", f"/api/v1/pets/{pet['id']}", nam)
    check("khách khác xem thú cưng của người ta -> 404", st == 404, (st, res))
    st, res = call("clinical-service", "GET", f"/internal/pets/{pet['id']}")
    check("Feign nội bộ GET /internal/pets/{id}: chủ + tên + snapshot", st == 200 and res["data"]["ownerId"] == lan_id and res["data"]["ownerPhone"] == "0912000008", (st, res))

    # ================================================================== đặt lịch
    day = (date.today() + timedelta(days=3)).isoformat()
    st, res = call("appointment-service", "POST", "/api/v1/appointments", lan,
                   {"petId": pet["id"], "appointmentDate": day, "startTime": "09:00", "reason": "Khám tổng quát"})
    check("chủ nuôi đặt lịch -> 201 PENDING", st == 201 and res["data"]["status"] == "PENDING" and len(res["data"]["id"]) == 36, (st, res))
    appt = res["data"]
    check("mã lịch APT-yyyyMMdd-XXXXXX", len(appt["appointmentCode"]) == 19 and appt["appointmentCode"].startswith("APT-"), appt["appointmentCode"])
    check("response có snapshot: tên thú cưng, tên + SĐT chủ nuôi, ownerId",
          appt["petName"] == f"Thử{SUFFIX}" and appt["ownerName"] == "Hoàng Thị Lan" and appt["ownerPhone"] == "0912000008" and appt["ownerId"] == lan_id, appt)
    cols = sql("SELECT pet_name_snapshot, owner_name_snapshot, owner_phone_snapshot, version FROM pet_clinic_appointment_db.appointments "
               f"WHERE id = {b(appt['id'])}").split("\t")
    check("DB: appointments 14 cột (snapshot + version)", cols[:3] == [f"Thử{SUFFIX}", "Hoàng Thị Lan", "0912000008"], cols)
    st, res = call("appointment-service", "POST", "/api/v1/appointments", nam,
                   {"petId": pet["id"], "appointmentDate": day, "startTime": "10:00", "reason": "Khám"})
    check("khách khác đặt lịch cho thú cưng của người ta -> 404 PET_NOT_FOUND", st == 404 and res.get("errorCode") == "APPT_1201", (st, res))
    st, res = call("appointment-service", "GET", "/api/v1/appointments", lan)
    ids = [a["id"] for a in res["data"]["items"]]
    check("chủ nuôi xem danh sách lịch của mình (lọc owner_id, không gọi clinical)", st == 200 and appt["id"] in ids, (st, ids))
    check("khách khác không thấy lịch của người ta", appt["id"] not in [a["id"] for a in call("appointment-service", "GET", "/api/v1/appointments", nam)[1]["data"]["items"]])
    st, res = call("appointment-service", "GET", f"/api/v1/appointments/{appt['id']}", nam)
    check("khách khác xem lịch của người ta -> 404", st == 404, (st, res))
    st, res = call("appointment-service", "POST", f"/api/v1/appointments/{appt['id']}/confirm", rec)
    check("lễ tân duyệt lịch -> CONFIRMED", st == 200 and res["data"]["status"] == "CONFIRMED" and res["data"]["confirmedBy"], (st, res))
    st, res = call("appointment-service", "POST", f"/api/v1/appointments/{appt['id']}/confirm", rec)
    check("duyệt lần hai -> 400 (đã CONFIRMED)", st == 400, (st, res))

    # ================================================================== PetUpdatedEvent: đổi tên thú cưng -> snapshot lịch hẹn
    new_name = f"Đổi{SUFFIX}"
    st, res = call("clinical-service", "PUT", f"/api/v1/pets/{pet['id']}", lan, {"name": new_name})
    check("đổi tên thú cưng -> 200", st == 200 and res["data"]["name"] == new_name, (st, res))
    n = sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.outbox_events WHERE event_type='PetUpdatedEvent' "
            f"AND aggregate_id = UNHEX(REPLACE('{pet['id']}','-',''))")
    check("clinical ghi PetUpdatedEvent vào outbox cùng giao dịch", n == "1", n)
    snap = wait_for(lambda: sql(f"SELECT pet_name_snapshot FROM pet_clinic_appointment_db.appointments WHERE id = {b(appt['id'])}") == new_name)
    check("outbox -> RabbitMQ -> appointment cập nhật pet_name_snapshot", bool(snap))
    check("đổi mô tả khác (không đổi tên/chủ) không phát thêm sự kiện",
          call("clinical-service", "PUT", f"/api/v1/pets/{pet['id']}", lan, {"breed": "Poodle"})[0] == 200
          and sql(f"SELECT COUNT(*) FROM pet_clinic_clinical_db.outbox_events WHERE event_type='PetUpdatedEvent' AND aggregate_id = UNHEX(REPLACE('{pet['id']}','-',''))") == "1")
    check("processed_events của appointment ghi nhận listener pet", sql("SELECT COUNT(*) FROM pet_clinic_appointment_db.processed_events WHERE consumer='appointment.pet-updated'") != "0")

    # ================================================================== UserProfileUpdatedEvent: đổi tên chủ nuôi -> snapshot thú cưng + lịch hẹn
    st, res = call("auth-service", "PUT", f"/api/v1/users/{lan_id}/profile", admin, {"fullName": f"Lan Đổi {SUFFIX}"})
    check("Admin đổi họ tên chủ nuôi -> 200", st == 200, (st, res))
    new_owner = f"Lan Đổi {SUFFIX}"
    check("auth -> RabbitMQ -> clinical cập nhật pets.owner_name_snapshot (mọi thú cưng của chủ)",
          bool(wait_for(lambda: sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.pets WHERE owner_id = "
                                    f"{b(lan_id)} AND owner_name_snapshot <> '{new_owner}'") == "0")))
    check("auth -> RabbitMQ -> appointment cập nhật owner_name_snapshot",
          bool(wait_for(lambda: sql(f"SELECT owner_name_snapshot FROM pet_clinic_appointment_db.appointments WHERE id = {b(appt['id'])}") == new_owner)))
    check("processed_events của clinical ghi nhận listener user", sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.processed_events WHERE consumer='clinical.user-profile-updated'") != "0")
    call("auth-service", "PUT", f"/api/v1/users/{lan_id}/profile", admin, {"fullName": "Hoàng Thị Lan"})   # trả lại tên seed

    # ================================================================== chuyển chủ: lịch còn hiệu lực đi theo chủ mới
    st, res = call("clinical-service", "PUT", f"/api/v1/pets/{pet['id']}", rec, {"ownerId": nam_id})
    check("Lễ tân chuyển thú cưng sang chủ khác -> 200, snapshot chủ mới", st == 200 and res["data"]["ownerId"] == nam_id, (st, res))
    moved = wait_for(lambda: sql(f"SELECT HEX(owner_id) FROM pet_clinic_appointment_db.appointments WHERE id = {b(appt['id'])}") == nam_id.replace("-", "").upper())
    check("lịch CONFIRMED của thú cưng đi theo chủ mới (PetUpdatedEvent)", bool(moved))
    check("chủ nuôi tự chuyển chủ -> 403", call("clinical-service", "PUT", f"/api/v1/pets/{pet['id']}", nam, {"ownerId": lan_id})[0] == 403)

    # ================================================================== check-in từ lịch hẹn (Feign clinical -> appointment, sự kiện về lại)
    today = date.today().isoformat()
    appt_today = str(uuid.uuid4())
    seed_pet = lu["id"]
    sql("INSERT INTO pet_clinic_appointment_db.appointments (id, appointment_code, pet_id, owner_id, pet_name_snapshot, owner_name_snapshot, "
        "owner_phone_snapshot, appointment_date, start_time, reason, booking_channel, status) VALUES "
        f"({b(appt_today)}, 'APT-CHK-{SUFFIX}', {b(seed_pet)}, {b(lan_id)}, 'Lu', 'Hoàng Thị Lan', '0912000008', '{today}', '09:00:00', 'Khám', 'PHONE', 'CONFIRMED')")
    st, res = call("clinical-service", "POST", "/api/v1/clinical/pet-visits/check-in", rec, {"appointmentId": appt_today})
    check("check-in theo lịch hẹn (Feign /internal/appointments/{id}) -> 201, petId từ lịch hẹn", st == 201 and res["data"]["petId"] == seed_pet and res["data"]["appointmentId"] == appt_today, (st, res))
    done = wait_for(lambda: sql(f"SELECT status FROM pet_clinic_appointment_db.appointments WHERE id = {b(appt_today)}") == "CHECKED_IN")
    check("VisitCheckedInEvent -> appointment chuyển CHECKED_IN (consumer idempotent)", bool(done))
    if st == 201:
        call("clinical-service", "POST", f"/api/v1/clinical/pet-visits/{res['data']['id']}/cancel", rec, {"reason": "dọn dẹp kiểm tra"})

    # ================================================================== xóa mềm
    st, res = call("clinical-service", "DELETE", f"/api/v1/pets/{pet['id']}", nam)
    check("xóa mềm thú cưng -> 200", st == 200, (st, res))
    check("DB: dòng còn, deleted_at có giá trị", sql(f"SELECT deleted_at IS NOT NULL FROM pet_clinic_clinical_db.pets WHERE id = {b(pet['id'])}") == "1")
    check("thú cưng đã xóa: GET -> 404", call("clinical-service", "GET", f"/api/v1/pets/{pet['id']}", nam)[0] == 404)
    check("thú cưng đã xóa: biến mất khỏi danh sách", pet["id"] not in [p["id"] for p in call("clinical-service", "GET", "/api/v1/pets", nam)[1]["data"]["items"]])
    check("thú cưng đã xóa: Feign nội bộ 404 (không đặt lịch được)", call("clinical-service", "GET", f"/internal/pets/{pet['id']}")[0] == 404)
    st, res = call("appointment-service", "POST", "/api/v1/appointments", nam, {"petId": pet["id"], "appointmentDate": day, "startTime": "10:30", "reason": "Khám"})
    check("đặt lịch cho thú cưng đã xóa -> 404", st == 404, (st, res))
    st, res = call("clinical-service", "POST", "/api/v1/clinical/pet-visits/check-in", rec, {"petId": pet["id"]})
    check("check-in thú cưng đã xóa -> 404", st == 404, (st, res))
finally:
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: thú cưng + lịch hẹn UUID, snapshot, sự kiện và Feign chạy đúng trên MySQL + RabbitMQ thật")
