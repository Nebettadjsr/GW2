"""Rendered-configuration checks for the container stack (STORY-LAUNCH-003).

Static only: `docker compose config` renders YAML and never contacts a Docker daemon. The proxy
logging check runs a throwaway nginx on loopback against a stub backend and skips if nginx is absent.
All credentials here are synthetic.
"""
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCKER = ROOT / "docker"
PROD = DOCKER / "compose.production.yaml"
LOCAL = DOCKER / "compose.yaml"
GUID = "123e4567-e89b-12d3-a456-426614174000"
HAVE_COMPOSE = shutil.which("docker") is not None and subprocess.run(
    ["docker", "compose", "version"], capture_output=True).returncode == 0
DROPPED_PREFIXES = ("GW2_", "DATABASE_", "POSTGRES_", "PG", "VITE_", "COMPOSE_", "SPRING_", "ICON_", "OPS_")
# Developer values that must never reach the containers.
POISON = {
    "DATABASE_URL": "jdbc:postgresql://maintainer-db.invalid:5432/prod",
    "DATABASE_USER": "maintainer",
    "DATABASE_PASSWORD": "poison-db-password",
    "GW2_API_KEY": "POISON-GW2-KEY",
}


def render(file, env_extra, use_poison=True):
    # Keep the platform/tool-discovery environment (HOME/USERPROFILE/APPDATA/SYSTEMROOT, DOCKER_*) so the
    # installed Compose CLI plugin is found; drop everything that could carry application credentials.
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith(DROPPED_PREFIXES)}
    if use_poison:
        env.update(POISON)
    env.update(env_extra)
    return subprocess.run(
        ["docker", "compose", "-f", str(file), "config", "--format", "json"],
        cwd=DOCKER, env=env, capture_output=True, text=True)


def rendered(file, env_extra, use_poison=True):
    result = render(file, env_extra, use_poison)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout), result.stdout


@unittest.skipUnless(HAVE_COMPOSE, "docker compose CLI not available")
class ProductionComposeTest(unittest.TestCase):
    def test_database_password_is_required(self):
        for env in ({}, {"GW2_STACK_DB_PASSWORD": ""}):
            result = render(PROD, env)
            self.assertNotEqual(0, result.returncode, env)
            self.assertIn("GW2_STACK_DB_PASSWORD", result.stderr)

    def test_no_local_default_password_and_no_developer_inheritance(self):
        cfg, text = rendered(PROD, {"GW2_STACK_DB_PASSWORD": "synthetic-prod-secret"})
        backend = cfg["services"]["backend"]["environment"]
        self.assertEqual("synthetic-prod-secret", backend["DATABASE_PASSWORD"])
        self.assertEqual("synthetic-prod-secret",
                         cfg["services"]["postgres"]["environment"]["POSTGRES_PASSWORD"])
        self.assertEqual("jdbc:postgresql://postgres:5432/gw2", backend["DATABASE_URL"])
        self.assertEqual("gw2", backend["DATABASE_USER"])
        self.assertNotIn("gw2-local-only", text)
        for value in POISON.values():
            self.assertNotIn(value, text)
        self.assertNotIn("GW2_API_KEY", backend)

    def test_owner_forwarding_blank_valid_and_malformed(self):
        base = {"GW2_STACK_DB_PASSWORD": "s"}
        for given, expected in (({}, ""), ({"GW2_OWNER_ACCOUNT_GUID": ""}, ""),
                                ({"GW2_OWNER_ACCOUNT_GUID": GUID}, GUID),
                                ({"GW2_OWNER_ACCOUNT_GUID": "not-a-guid"}, "not-a-guid")):
            cfg, _ = rendered(PROD, {**base, **given})
            # Forwarded verbatim; the backend's existing policy decides (blank: nobody, malformed: fails).
            self.assertEqual(expected, cfg["services"]["backend"]["environment"]["GW2_OWNER_ACCOUNT_GUID"])

    def test_only_frontend_publishes_loopback_by_default(self):
        cfg, _ = rendered(PROD, {"GW2_STACK_DB_PASSWORD": "s"})
        self.assertNotIn("ports", cfg["services"]["postgres"])
        self.assertNotIn("ports", cfg["services"]["backend"])
        self.assertNotIn("expose", cfg["services"]["postgres"])
        ports = cfg["services"]["frontend"]["ports"]
        self.assertEqual(1, len(ports))
        self.assertEqual("127.0.0.1", ports[0]["host_ip"])
        self.assertEqual(8080, ports[0]["target"])

    def test_writable_volumes_restart_health_and_no_forwarded_trust(self):
        cfg, text = rendered(PROD, {"GW2_STACK_DB_PASSWORD": "s"})
        services = cfg["services"]
        targets = lambda name: {v["target"] for v in services[name]["volumes"]}
        self.assertEqual({"/var/lib/postgresql/data"}, targets("postgres"))
        self.assertEqual({"/app/data/icons", "/app/data/ops"}, targets("backend"))
        env = services["backend"]["environment"]
        self.assertEqual("/app/data/icons", env["ICON_CACHE_DIR"])
        self.assertEqual("/app/data/ops", env["OPS_HISTORY_DIR"])
        for svc in services.values():
            self.assertEqual("unless-stopped", svc["restart"])
            self.assertIn("test", svc["healthcheck"])
        self.assertNotIn("forward-headers", text.lower())
        self.assertNotIn("FORWARD_HEADERS", text)

    def test_log_bounds_for_all_services_and_override(self):
        for extra, size, count in (({}, "10m", "3"),
                                   ({"GW2_STACK_LOG_MAX_SIZE": "1m", "GW2_STACK_LOG_MAX_FILE": "2"}, "1m", "2")):
            cfg, _ = rendered(PROD, {"GW2_STACK_DB_PASSWORD": "s", **extra})
            for name in ("postgres", "backend", "frontend"):
                logging = cfg["services"][name]["logging"]
                self.assertEqual("json-file", logging["driver"], name)
                self.assertEqual({"max-size": size, "max-file": count}, logging["options"], name)

    def test_operator_facts_are_build_args_only(self):
        facts = {"VITE_PUBLIC_DOMAIN": "example.invalid", "VITE_CONTACT_EMAIL": "x@example.invalid"}
        cfg, _ = rendered(PROD, {"GW2_STACK_DB_PASSWORD": "s", **facts})
        args = cfg["services"]["frontend"]["build"]["args"]
        self.assertEqual("example.invalid", args["VITE_PUBLIC_DOMAIN"])
        self.assertEqual("", args["VITE_OPERATOR_NAME"])  # unknown stays unset
        self.assertNotIn("VITE_PUBLIC_DOMAIN", cfg["services"]["frontend"].get("environment", {}))


@unittest.skipUnless(HAVE_COMPOSE, "docker compose CLI not available")
class LocalComposeTest(unittest.TestCase):
    def test_local_defaults_stay_usable_and_isolated(self):
        cfg, text = rendered(LOCAL, {})
        backend = cfg["services"]["backend"]["environment"]
        self.assertEqual("gw2-local-only", backend["DATABASE_PASSWORD"])
        self.assertEqual("", backend["GW2_OWNER_ACCOUNT_GUID"])
        for value in POISON.values():
            self.assertNotIn(value, text)
        for name in ("postgres", "backend", "frontend"):
            self.assertEqual("3", cfg["services"][name]["logging"]["options"]["max-file"])


class _Stub(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"{}")

    do_POST = do_GET

    def log_message(self, *args):
        pass


@unittest.skipUnless(shutil.which("nginx"), "nginx not installed")
class ProxyLoggingTest(unittest.TestCase):
    SECRETS = ("SECRET-KEY-VALUE", "SECRET-COOKIE-VALUE", "SECRET-QUERY-VALUE", "SECRET-CTX-VALUE", "SECRET-BODY-VALUE")

    def request(self, port, path, body=None):
        req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=body, headers={
            "X-GW2-API-Key": self.SECRETS[0], "Cookie": "sid=" + self.SECRETS[1],
            "X-Account-Context": self.SECRETS[3], "Referer": "http://x/?t=" + self.SECRETS[2]})
        try:
            return urllib.request.urlopen(req, timeout=5).status
        except urllib.error.HTTPError as e:
            return e.code

    def test_access_and_error_logs_leak_no_credentials_or_query(self):
        tmp = Path(tempfile.mkdtemp(prefix="gw2-nginx-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        stub = HTTPServer(("127.0.0.1", 0), _Stub)
        threading.Thread(target=stub.serve_forever, daemon=True).start()
        template = (ROOT / "frontend" / "default.conf.template").read_text()
        site = template.replace("${GW2_BACKEND_ORIGIN}", f"http://127.0.0.1:{stub.server_port}")
        site = site.replace("listen 8080;", "listen 127.0.0.1:18087;").replace(
            "/usr/share/nginx/html", str(tmp)).replace("/dev/stdout", str(tmp / "access.log")).replace(
            "/dev/stderr", str(tmp / "server-error.log"))
        (tmp / "index.html").write_text("ok")
        conf = tmp / "nginx.conf"
        conf.write_text(f"""pid {tmp}/nginx.pid;
error_log {tmp}/error.log info;
events {{}}
http {{
  access_log off;
  client_body_temp_path {tmp}/b; proxy_temp_path {tmp}/p; fastcgi_temp_path {tmp}/f;
  uwsgi_temp_path {tmp}/u; scgi_temp_path {tmp}/s;
{site}
}}
""")
        check = subprocess.run(["nginx", "-t", "-p", str(tmp), "-c", str(conf)], capture_output=True, text=True)
        self.assertEqual(0, check.returncode, check.stderr)
        proc = subprocess.Popen(["nginx", "-p", str(tmp), "-c", str(conf), "-g", "daemon off;"],
                                stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: (proc.terminate(), proc.wait(10)))
        for _ in range(50):
            try:
                self.request(18087, "/index.html")
                break
            except OSError:
                time.sleep(0.1)
        self.assertEqual(200, self.request(18087, "/api/system/status?access_token=" + self.SECRETS[2]))
        stub.shutdown()
        stub.server_close()  # upstream now refuses connections: exercises the error path
        self.assertEqual(502, self.request(18087, "/api/x?access_token=" + self.SECRETS[2]))
        # Request-associated CRIT failure: the body temp directory vanishes, so buffering a large POST fails
        # while the request line, query and Referer are attached to the log context.
        shutil.rmtree(tmp / "b")
        failed = self.request(18087, "/api/y?access_token=" + self.SECRETS[2], body=b"SECRET-BODY-VALUE" * 4000)
        self.assertEqual(500, failed)
        time.sleep(0.3)
        access = (tmp / "access.log").read_text()
        self.assertIn("/api/system/status 200", access)
        self.assertIn("/api/x 502", access)
        everything = "".join(p.read_text() for p in tmp.glob("*.log"))
        for secret in self.SECRETS:
            self.assertNotIn(secret, everything)
        self.assertNotIn("access_token", everything)
        self.assertIn("/api/y 500", access)


if __name__ == "__main__":
    unittest.main()
