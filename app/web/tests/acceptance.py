#!/usr/bin/env python3
# coding: utf-8
"""Browser -> Vite -> Java -> Python -> disposable PostgreSQL/LanceDB acceptance.
Uses a deterministic Agent model and real parsing/retrieval, without calling paid providers.
Requires running infra PostgreSQL, an existing haiku-rag-local:latest image, built Java JAR and pnpm build.
"""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

WEB = Path(__file__).resolve().parents[1]
ROOT = WEB.parents[1]
JAVA = ROOT / "app/backend-java"
COMPOSE = ["docker", "compose", "--env-file", "infra/.env", "-f", "infra/compose.postgres.yml"]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait(url, timeout=120):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(0.3)
    raise RuntimeError("Test service did not become ready: " + url)


def main():
    suffix = uuid.uuid4().hex[:10]
    database = "paper_web_test_" + suffix
    container = "paper-web-test-" + suffix
    java_port, python_port, web_port = free_port(), free_port(), free_port()
    config = json.loads(subprocess.check_output(COMPOSE + ["config", "--format", "json"], cwd=str(ROOT)))
    secret = config["services"]["postgres"]["environment"]["POSTGRES_PASSWORD"]
    processes = []
    database_created = container_started = False

    def sql(statement, db="postgres"):
        return subprocess.check_output(COMPOSE + ["exec", "-T", "postgres", "psql", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-U", "paper_assistant", "-d", db, "-c", statement], cwd=str(ROOT), stderr=subprocess.STDOUT)

    def stop(process):
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    with tempfile.TemporaryDirectory(prefix="paper-web-files-") as storage, tempfile.TemporaryDirectory(prefix="paper-web-index-") as index, tempfile.TemporaryFile() as java_log, tempfile.TemporaryFile() as web_log:
        try:
            sql("CREATE DATABASE " + database)
            database_created = True
            subprocess.check_output(["docker", "run", "-d", "--pull=never", "--name", container,
                "-p", "127.0.0.1:" + str(python_port) + ":8000", "-v", index + ":/test-data",
                "-e", "CHAT_TEST_DATABASE_PATH=/test-data/test.lancedb", "-e", "HAIKU_RAG_CONFIG_PATH=/tmp/no-config.yaml",
                "-e", "PYTHONPATH=/workspace/haiku_rag_slim:/workspace/app/backend", "-e", "LOGFIRE_IGNORE_NO_CONFIG=1",
                "-v", str(ROOT) + ":/workspace:ro", "-w", "/workspace/app/backend/tests", "--entrypoint", "python",
                "haiku-rag-local:latest", "-m", "uvicorn", "chat_server:app", "--host", "0.0.0.0", "--port", "8000"])
            container_started = True
            wait("http://127.0.0.1:" + str(python_port) + "/health")
            env = os.environ.copy()
            env.update(SPRING_PROFILES_ACTIVE="postgres", POSTGRES_URL="jdbc:postgresql://127.0.0.1:5432/" + database,
                POSTGRES_USER="paper_assistant", POSTGRES_PASSWORD=secret, SERVER_PORT=str(java_port), PAPER_STORAGE_ROOT=storage,
                RAG_BASE_URL="http://127.0.0.1:" + str(python_port), RAG_NAMESPACE="web-tests", PAPER_INGESTION_ENABLED="true",
                RAG_POLL_INTERVAL="200ms", RAG_RETRY_DELAY="300ms", RAG_REQUEST_TIMEOUT="120s", RAG_LEASE_DURATION="155s", RAG_CHAT_TIMEOUT="150s")
            java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else shutil.which("java")
            processes.append(subprocess.Popen([java, "-jar", str(JAVA / "target/paper-assistant-backend-0.0.1-SNAPSHOT.jar")], cwd=str(JAVA), env=env, stdout=java_log, stderr=subprocess.STDOUT))
            java_url = "http://127.0.0.1:" + str(java_port)
            wait(java_url + "/actuator/health")
            web_env = os.environ.copy()
            web_env["JAVA_API_TARGET"] = java_url
            # Run Vite directly so teardown owns its process, not only a package-manager shell.
            processes.append(subprocess.Popen([shutil.which("node"), str(WEB / "node_modules/vite/bin/vite.js"), "preview", "--host", "127.0.0.1", "--port", str(web_port)], cwd=str(WEB), env=web_env, stdout=web_log, stderr=subprocess.STDOUT))
            url = "http://127.0.0.1:" + str(web_port)
            wait(url)
            test_env = os.environ.copy()
            test_env.update(E2E_BASE_URL=url, E2E_REAL_BACKEND="1")
            subprocess.check_call(["pnpm", "exec", "playwright", "test", "real-backend.spec.ts", "--output", "test-results/real"], cwd=str(WEB), env=test_env)
            count = sql("SELECT count(*) FROM conversation_turns WHERE status='COMPLETED'", database).decode().strip()
            if count != "2":
                raise AssertionError("Expected two committed turns in PostgreSQL")
            print("PASS: browser upload, automatic ingestion, cited multi-turn answers, reload and PostgreSQL persistence.", flush=True)
        finally:
            for process in reversed(processes):
                stop(process)
            if container_started:
                subprocess.check_call(["docker", "rm", "-f", container], stdout=subprocess.DEVNULL)
            if database_created:
                sql("DROP DATABASE " + database + " WITH (FORCE)")
            print("Disposable services and test data cleaned up.", flush=True)


if __name__ == "__main__":
    main()
