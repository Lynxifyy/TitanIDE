"""TITAN remote build agent.

Accepts a project archive plus the commands TITAN planned, runs them in an isolated
working directory, and exposes the log and artifacts for the app to poll.

The agent never trusts the client: entry names are validated against path traversal,
the archive is bounded, the command list is bounded, and every command runs with the
working directory pinned inside the job sandbox.

Standard library only, so it runs on a bare Linux box with Python 3.9+.
"""

from __future__ import annotations

import base64
import io
import json
import os
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional

MAX_ARCHIVE_BYTES = 96 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_COMMANDS = 24
MAX_LOG_CHARS = 2_000_000
DEFAULT_TIMEOUT_SECONDS = 1800


@dataclass
class Job:
    job_id: str
    project_id: str
    kind: str
    commands: List[List[str]]
    timeout_seconds: int
    artifact_globs: List[str]
    state: str = "queued"
    exit_code: Optional[int] = None
    failure_reason: Optional[str] = None
    artifacts: List[Dict[str, Any]] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)

    def __post_init__(self) -> None:
        self._lock = threading.Lock()
        self._log: List[str] = []
        self._process: Optional[subprocess.Popen] = None
        self._cancelled = False

    def append(self, text: str) -> None:
        with self._lock:
            if not text:
                return
            self._log.append(text)
            total = sum(len(line) for line in self._log)
            while total > MAX_LOG_CHARS and len(self._log) > 1:
                total -= len(self._log.pop(0))

    @property
    def log(self) -> str:
        with self._lock:
            return "".join(self._log)

    def cancel(self) -> None:
        with self._lock:
            self._cancelled = True
            process = self._process
        if process and process.poll() is None:
            try:
                process.terminate()
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()

    @property
    def cancelled(self) -> bool:
        with self._lock:
            return self._cancelled


class JobStore:
    def __init__(self, workspace: str) -> None:
        self._workspace = workspace
        self._jobs: Dict[str, Job] = {}
        self._lock = threading.Lock()

    def add(self, job: Job) -> None:
        with self._lock:
            self._jobs[job.job_id] = job

    def get(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self._jobs.get(job_id)

    def job_dir(self, job_id: str) -> str:
        return os.path.join(self._workspace, job_id)


def _safe_relative(name: str) -> Optional[str]:
    """Returns a normalized relative path, or None when the entry is unsafe."""
    if not name or name.startswith("/") or "\\" in name or ":" in name:
        return None
    parts = [part for part in name.split("/") if part not in ("", ".")]
    if any(part == ".." for part in parts):
        return None
    return "/".join(parts) if parts else None


def extract_archive(payload: bytes, destination: str) -> None:
    """Extracts a zip archive, rejecting traversal and bounding size and entry count."""
    os.makedirs(destination, exist_ok=True)
    written = 0
    total = 0
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if len(archive.infolist()) > MAX_ENTRIES:
            raise ValueError(f"Archive has more than {MAX_ENTRIES} entries")
        for entry in archive.infolist():
            relative = _safe_relative(entry.filename)
            if relative is None:
                raise ValueError(f"Unsafe archive entry: {entry.filename!r}")
            target = os.path.join(destination, relative)
            if not os.path.abspath(target).startswith(os.path.abspath(destination) + os.sep):
                raise ValueError(f"Entry escapes the sandbox: {entry.filename!r}")
            if entry.is_dir():
                os.makedirs(target, exist_ok=True)
                continue
            total += entry.file_size
            if total > MAX_UNCOMPRESSED_BYTES:
                raise ValueError(f"Archive expands beyond {MAX_UNCOMPRESSED_BYTES} bytes")
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with archive.open(entry) as source, open(target, "wb") as sink:
                shutil.copyfileobj(source, sink)
            mode = entry.external_attr >> 16
            if mode & 0o111:
                os.chmod(target, mode | 0o700)
            written += 1
    if written == 0:
        raise ValueError("Archive contained no files")


def _build_environment(job_dir: str) -> Dict[str, str]:
    environment = {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "HOME": job_dir,
        "LANG": "C.UTF-8",
        "GRADLE_USER_HOME": os.path.join(job_dir, ".gradle-home"),
        "GRADLE_OPTS": "-Dorg.gradle.daemon=false",
    }
    for name in ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "CARGO_HOME", "GOPATH"):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    return environment


def run_job(job: Job, job_dir: str) -> None:
    job.state = "running"
    source_dir = os.path.join(job_dir, "src")
    environment = _build_environment(job_dir)
    started = time.time()

    try:
        for command in job.commands:
            if job.cancelled:
                job.state = "cancelled"
                job.append("[agent] cancelled before command finished\n")
                return
            job.append(f"[agent] $ {' '.join(command)}\n")
            process = subprocess.Popen(
                command,
                cwd=source_dir,
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                start_new_session=True,
            )
            with job._lock:
                job._process = process
            assert process.stdout is not None
            with process.stdout:
                for line in process.stdout:
                    job.append(line)
            exit_code = process.wait()
            with job._lock:
                job._process = None

            if job.cancelled:
                job.state = "cancelled"
                return
            if exit_code != 0:
                job.exit_code = exit_code
                job.state = "failed"
                job.failure_reason = f"Command exited with code {exit_code}"
                job.append(f"[agent] failed with exit code {exit_code}\n")
                return
            if time.time() - started > job.timeout_seconds:
                job.exit_code = exit_code
                job.state = "failed"
                job.failure_reason = f"Job exceeded {job.timeout_seconds}s"
                return

        job.exit_code = 0
        job.state = "succeeded"
        job.artifacts = _collect_artifacts(job, source_dir, job_dir)
        job.append(f"[agent] finished in {time.time() - started:.1f}s\n")
    except FileNotFoundError as error:
        job.state = "failed"
        job.failure_reason = f"Toolchain missing on the agent: {error}"
        job.append(f"[agent] {job.failure_reason}\n")
    except Exception as error:  # noqa: BLE001 - the reason must reach the client
        job.state = "failed"
        job.failure_reason = f"Agent error: {error}"
        job.append(f"[agent] {job.failure_reason}\n")
    finally:
        with job._lock:
            job._process = None


def _collect_artifacts(job: Job, source_dir: str, job_dir: str) -> List[Dict[str, Any]]:
    collected: List[Dict[str, Any]] = []
    artifact_dir = os.path.join(job_dir, "artifacts")
    os.makedirs(artifact_dir, exist_ok=True)
    for pattern in job.artifact_globs[:20]:
        for path in sorted(_glob(source_dir, pattern)):
            name = os.path.basename(path)
            target = os.path.join(artifact_dir, name)
            try:
                shutil.copy2(path, target)
            except OSError:
                continue
            collected.append(
                {"name": name, "url": f"/v1/jobs/{job.job_id}/artifacts/{name}", "size_bytes": os.path.getsize(target)}
            )
    return collected


def _glob(root: str, pattern: str) -> List[str]:
    import glob as glob_module

    matches = glob_module.glob(os.path.join(root, pattern), recursive=True)
    return [match for match in matches if os.path.isfile(match)]


class AgentHandler(BaseHTTPRequestHandler):
    server_version = "TitanBuildAgent/1.0"
    store: JobStore
    token: Optional[str]

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        print(f"[agent] {self.address_string()} {format % args}", flush=True)

    # -- helpers ---------------------------------------------------------

    def _authorized(self) -> bool:
        if not self.token:
            return True
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {self.token}"

    def _send_json(self, status: HTTPStatus, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return b""
        if length > MAX_ARCHIVE_BYTES * 2:
            raise ValueError("Request body too large")
        return self.rfile.read(length)

    # -- routes ----------------------------------------------------------

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if not self._authorized():
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return

        path = self.path.rstrip("/")
        if path.endswith("/cancel") and path.startswith("/v1/jobs/"):
            job_id = path.split("/")[3]
            job = self.store.get(job_id)
            if job is None:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown job"})
                return
            job.cancel()
            self._send_json(HTTPStatus.OK, {"job_id": job_id, "state": "cancelling"})
            return

        if path == "/v1/jobs":
            try:
                payload = json.loads(self._read_body() or b"{}")
            except (ValueError, json.JSONDecodeError) as error:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": f"invalid body: {error}"})
                return
            self._handle_submit(payload)
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown route"})

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if not self._authorized():
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return

        path = self.path.rstrip("/")
        if path == "/v1/health":
            self._send_json(HTTPStatus.OK, {"status": "ok"})
            return
        if path.startswith("/v1/jobs/") and "/artifacts/" in path:
            job_id, _, name = path[len("/v1/jobs/") :].partition("/artifacts/")
            self._send_artifact(job_id, name)
            return
        if path.startswith("/v1/jobs/"):
            job_id = path[len("/v1/jobs/") :]
            job = self.store.get(job_id)
            if job is None:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown job"})
                return
            self._send_json(
                HTTPStatus.OK,
                {
                    "job_id": job.job_id,
                    "state": job.state,
                    "exit_code": job.exit_code,
                    "log": job.log,
                    "log_offset": len(job.log),
                    "failure_reason": job.failure_reason,
                    "artifacts": job.artifacts,
                },
            )
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown route"})

    def _handle_submit(self, payload: Dict[str, Any]) -> None:
        commands = payload.get("commands") or []
        if not isinstance(commands, list) or not commands:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "commands must be a non-empty list"})
            return
        if len(commands) > MAX_COMMANDS:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": f"at most {MAX_COMMANDS} commands"})
            return
        normalized: List[List[str]] = []
        for command in commands:
            if not isinstance(command, list) or not command or not all(isinstance(part, str) for part in command):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "each command must be a list of strings"})
                return
            normalized.append(command)

        try:
            archive = base64.b64decode(payload.get("source_archive") or "")
        except (ValueError, TypeError) as error:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": f"invalid archive: {error}"})
            return
        if not archive:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "source_archive is required"})
            return
        if len(archive) > MAX_ARCHIVE_BYTES:
            self._send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "archive too large"})
            return

        job = Job(
            job_id=uuid.uuid4().hex,
            project_id=str(payload.get("project_id", "")),
            kind=str(payload.get("kind", "BUILD")),
            commands=normalized,
            timeout_seconds=int(payload.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS),
            artifact_globs=[str(glob) for glob in (payload.get("artifact_globs") or [])][:20],
        )
        job_dir = self.store.job_dir(job.job_id)
        try:
            extract_archive(archive, os.path.join(job_dir, "src"))
        except (ValueError, zipfile.BadZipFile) as error:
            shutil.rmtree(job_dir, ignore_errors=True)
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": f"could not unpack project: {error}"})
            return

        self.store.add(job)
        threading.Thread(target=run_job, args=(job, job_dir), daemon=True).start()
        self._send_json(HTTPStatus.ACCEPTED, {"job_id": job.job_id})

    def _send_artifact(self, job_id: str, name: str) -> None:
        job = self.store.get(job_id)
        if job is None or name not in {artifact["name"] for artifact in job.artifacts}:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "unknown artifact"})
            return
        path = os.path.join(self.store.job_dir(job_id), "artifacts", os.path.basename(name))
        if not os.path.isfile(path):
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "artifact missing"})
            return
        with open(path, "rb") as handle:
            data = handle.read()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def serve(host: str = "127.0.0.1", port: int = 8787, workspace: Optional[str] = None, token: Optional[str] = None) -> None:
    workspace = workspace or tempfile.mkdtemp(prefix="titan-agent-")
    os.makedirs(workspace, exist_ok=True)

    handler = type("BoundAgentHandler", (AgentHandler,), {"store": JobStore(workspace), "token": token})
    httpd = ThreadingHTTPServer((host, port), handler)

    def shutdown(signum: int, frame: Any) -> None:  # noqa: ARG001
        print("[agent] shutting down", flush=True)
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    print(f"[agent] listening on http://{host}:{port} (workspace {workspace})", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="TITAN remote build agent")
    parser.add_argument("--host", default=os.environ.get("TITAN_AGENT_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("TITAN_AGENT_PORT", "8787")))
    parser.add_argument("--workspace", default=os.environ.get("TITAN_AGENT_WORKSPACE"))
    parser.add_argument("--token", default=os.environ.get("TITAN_AGENT_TOKEN"))
    arguments = parser.parse_args()
    serve(arguments.host, arguments.port, arguments.workspace, arguments.token)
