"""Kiểm tra THẬT bước 8.1: Feign có timeout 2 giây và Circuit Breaker (Resilience4j), chạy appointment-service trên MySQL tạm với một "auth-service giả".
  python scripts/feign_resilience_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh appointment-service --reset), JDK 21, jar appointment-service trong target.
Auth giả (Python) đóng vai auth-service ở cổng 18095 và đổi được chế độ: ok / chậm 4 giây / 404 nghiệp vụ / 500. Dùng endpoint công khai
GET /api/v1/appointments/slots (tính sức chứa bằng cách hỏi auth-service qua Feign, KHÔNG có fallback - lỗi thì dừng, không đoán).
Kiểm tra: (1) lỗi nghiệp vụ 4xx của service con KHÔNG làm mở mạch; (2) gọi chậm bị cắt ở ~2 giây (không phải 5 giây cũ); (3) sau 10 lần chậm mạch MỞ:
lời gọi kế tiếp bị từ chối NGAY (<0.5 giây) và auth giả KHÔNG nhận thêm yêu cầu nào; (4) hết thời gian chờ mạch nửa mở, auth khỏe lại thì mạch đóng;
(5) lỗi 5xx của service con làm mở mạch."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL = "vetclinic-check-mysql"
APPT_PORT, AUTH_PORT = 18082, 18095
FAILS = []
state = {"mode": "ok", "hits": 0}


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


class FakeAuth(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        state["hits"] += 1
        mode = state["mode"]
        if mode == "slow":
            time.sleep(4)
        body, status = {"status": 200, "data": 2}, 200
        if mode == "notfound":
            body, status = {"status": 404, "errorCode": "AUTH_1101", "message": "Không tìm thấy người dùng"}, 404
        elif mode == "error500":
            body, status = {"status": 500, "errorCode": "SYS_1000", "message": "Lỗi hệ thống"}, 500
        data = json.dumps(body).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass   # client đã cắt vì timeout


def slots():
    """Trả (HTTP status, errorCode, số giây)."""
    url = f"http://localhost:{APPT_PORT}/api/v1/appointments/slots?date={(date.today() + timedelta(days=2)).isoformat()}"
    started = time.time()
    try:
        with urllib.request.urlopen(url, timeout=30) as res:
            return res.status, None, time.time() - started
    except urllib.error.HTTPError as e:
        try:
            code = json.loads(e.read() or b"{}").get("errorCode")
        except ValueError:
            code = None
        return e.code, code, time.time() - started


proc = None
server = ThreadingHTTPServer(("127.0.0.1", AUTH_PORT), FakeAuth)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    out = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout.split()
    if MYSQL not in out:
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh appointment-service --reset' trước")
    jar = os.path.join(ROOT, "vetclinic-backend", "appointment-service", "target", "appointment-service.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}")
    log = os.path.join(tempfile.gettempdir(), "feign-resilience-appointment.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC",
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost")
    proc = subprocess.Popen([JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={APPT_PORT}", "--eureka.client.enabled=false",
                             f"--spring.cloud.discovery.client.simple.instances.auth-service[0].uri=http://localhost:{AUTH_PORT}"],
                            stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            break
        if proc.poll() is not None:
            sys.exit(f"appointment-service không khởi động; xem {log}")

    # ---------------------------------------------------------------- (0) nền: auth khỏe
    st, code, took = slots()
    check("auth giả khỏe: slots trả 200", st == 200, (st, code))

    # ---------------------------------------------------------------- (1) lỗi nghiệp vụ 4xx không mở mạch
    state["mode"] = "notfound"
    results = [slots() for _ in range(15)]
    check("lỗi nghiệp vụ của service con được GIỮ NGUYÊN mã (404 AUTH_1101, không bị bọc thành 500 / 503 khi có Circuit Breaker)",
          all(r[0] == 404 and r[1] == "AUTH_1101" for r in results), results[:3])
    check("15 lần auth trả 404 nghiệp vụ: mỗi lần đều gọi thật tới auth (không bị chặn)", state["hits"] >= 16 and all(r[0] >= 400 for r in results), (state["hits"], results[:2]))
    state["mode"] = "ok"
    hits_before = state["hits"]
    st, code, took = slots()
    check("sau 15 lỗi 4xx mạch VẪN ĐÓNG: auth khỏe lại là gọi được ngay (200, có gọi tới auth)", st == 200 and state["hits"] == hits_before + 1, (st, code, state["hits"] - hits_before))

    # ---------------------------------------------------------------- (2) timeout 2 giây
    state["mode"] = "slow"
    st, code, took = slots()
    check(f"auth chậm 4 giây: lời gọi bị cắt ở ~2 giây ({took:.1f}s, không phải 5 giây của cấu hình cũ), 503 SYS_1004", st == 503 and code == "SYS_1004" and 1.7 <= took <= 3.5, (st, code, took))

    # ---------------------------------------------------------------- (3) mạch mở sau 10 lần chậm
    for _ in range(9):
        slots()
    hits_before = state["hits"]
    st, code, took = slots()
    check(f"sau 10 lần chậm mạch MỞ: lời gọi kế tiếp bị từ chối NGAY ({took:.2f}s), 503 SYS_1004", st == 503 and code == "SYS_1004" and took < 0.5, (st, code, took))
    time.sleep(1)
    check("auth giả KHÔNG nhận thêm yêu cầu nào khi mạch mở (bảo vệ service đang quá tải)", state["hits"] == hits_before, (hits_before, state["hits"]))
    state["mode"] = "ok"
    st, code, took = slots()
    check("mạch còn mở: dù auth đã khỏe lại vẫn từ chối ngay trong thời gian chờ (10 giây)", st == 503 and took < 0.5, (st, code, took))

    # ---------------------------------------------------------------- (4) nửa mở -> đóng
    time.sleep(10.5)
    results = [slots() for _ in range(5)]
    check("hết thời gian chờ, auth khỏe: các lời gọi thử rồi mọi lời gọi đều 200 (mạch đóng lại)", all(r[0] == 200 for r in results), results)

    # ---------------------------------------------------------------- (5) 5xx làm mở mạch
    state["mode"] = "error500"
    for _ in range(10):
        slots()
    state["mode"] = "ok"
    hits_before = state["hits"]
    st, code, took = slots()
    check("10 lần auth trả 500: mạch mở, lời gọi kế tiếp bị từ chối ngay dù auth đã khỏe (không gọi tới auth)", st == 503 and took < 0.5 and state["hits"] == hits_before, (st, code, took, state["hits"] - hits_before))
finally:
    if proc:
        proc.terminate()
    server.shutdown()

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: Feign timeout 2 giây + Circuit Breaker Resilience4j chạy đúng (lỗi 4xx không mở mạch, chậm / 5xx mở mạch, tự đóng lại)")
