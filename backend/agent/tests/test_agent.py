"""End-to-end tests for the TITAN build agent.

These start the real HTTP server on an ephemeral port, upload a real zip archive, and let
the agent run real subprocesses. Nothing is mocked: a passing test means the wire
protocol, the sandbox and the process runner all work together.
"""

from __future__ import annotations

import base64
import io
import json
import os
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import zipfile
from http.server import ThreadingHTTPServer

from titan_agent import AgentHandler, Job, JobStore, _safe_relative, extract_archive, run_job


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _zip(files: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    return buffer.getvalue()


class SafeRelativeTest(unittest.TestCase):
    def test_accepts_plain_paths(self) -> None:
        self.assertEqual(_safe_relative("src/main.kt"), "src/main.kt")
        self.assertEqual(_safe_relative("./a/b.txt"), "a/b.txt")

    def test_rejects_traversal_and_absolute_paths(self) -> None:
        for name in ("../etc/passwd", "/etc/passwd", "a/../../b", "C:/windows", "a\\b"):
            with self.subTest(name=name):
                self.assertIsNone(_safe_relative(name))


class ExtractArchiveTest(unittest.TestCase):
    def test_writes_files(self) -> None:
        with tempfile.TemporaryDirectory() as destination:
            extract_archive(_zip({"a.txt": "hello", "nested/b.txt": "world"}), destination)

            with open(os.path.join(destination, "a.txt")) as handle:
                self.assertEqual("hello", handle.read())
            with open(os.path.join(destination, "nested", "b.txt")) as handle:
                self.assertEqual("world", handle.read())

    def test_rejects_zip_slip(self) -> None:
        with tempfile.TemporaryDirectory() as destination:
            with self.assertRaises(ValueError):
                extract_archive(_zip({"../escape.txt": "x"}), destination)

    def test_rejects_empty_archive(self) -> None:
        with tempfile.TemporaryDirectory() as destination:
            with self.assertRaises(ValueError):
                extract_archive(_zip({}), destination)


class JobExecutionTest(unittest.TestCase):
    def test_successful_command_marks_job_succeeded(self) -> None:
        with tempfile.TemporaryDirectory() as job_dir:
            os.makedirs(os.path.join(job_dir, "src"))
            job = Job("j1", "p1", "BUILD", [["sh", "-c", "echo built"]], 30, [])

            run_job(job, job_dir)

            self.assertEqual("succeeded", job.state)
            self.assertEqual(0, job.exit_code)
            self.assertIn("built", job.log)

    def test_failing_command_records_exit_code(self) -> None:
        with tempfile.TemporaryDirectory() as job_dir:
            os.makedirs(os.path.join(job_dir, "src"))
            job = Job("j2", "p1", "TEST", [["sh", "-c", "echo boom; exit 3"]], 30, [])

            run_job(job, job_dir)

            self.assertEqual("failed", job.state)
            self.assertEqual(3, job.exit_code)
            self.assertIn("boom", job.log)
            self.assertIn("code 3", job.failure_reason or "")

    def test_missing_binary_reports_failure(self) -> None:
        with tempfile.TemporaryDirectory() as job_dir:
            os.makedirs(os.path.join(job_dir, "src"))
            job = Job("j3", "p1", "BUILD", [["definitely-not-a-real-binary-xyz"]], 30, [])

            run_job(job, job_dir)

            self.assertEqual("failed", job.state)
            self.assertIn("Toolchain missing", job.failure_reason or "")

    def test_artifacts_are_collected(self) -> None:
        with tempfile.TemporaryDirectory() as job_dir:
            os.makedirs(os.path.join(job_dir, "src"))
            job = Job(
                "j4",
                "p1",
                "BUILD",
                [["sh", "-c", "mkdir -p out && echo apk > out/app.apk"]],
                30,
                ["out/*.apk"],
            )

            run_job(job, job_dir)

            self.assertEqual("succeeded", job.state)
            self.assertEqual(1, len(job.artifacts))
            self.assertEqual("app.apk", job.artifacts[0]["name"])
            self.assertTrue(job.artifacts[0]["url"].endswith("/artifacts/app.apk"))

    def test_log_is_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as job_dir:
            os.makedirs(os.path.join(job_dir, "src"))
            job = Job("j5", "p1", "BUILD", [["sh", "-c", "yes x | head -n 20000"]], 60, [])

            run_job(job, job_dir)

            self.assertLess(len(job.log), 2_000_100)


class AgentHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workspace = tempfile.mkdtemp(prefix="titan-agent-test-")
        handler = type(
            "TestHandler",
            (AgentHandler,),
            {"store": JobStore(cls.workspace), "token": "secret-token"},
        )
        cls.port = _free_port()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", cls.port), handler)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def _request(self, method: str, path: str, body: dict | None = None, token: str = "secret-token"):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Authorization", f"Bearer {token}")
        if data:
            request.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"{}")

    def _await(self, job_id: str) -> dict:
        payload: dict = {}
        for _ in range(80):
            _, payload = self._request("GET", f"/v1/jobs/{job_id}")
            if payload["state"] in ("succeeded", "failed", "cancelled"):
                return payload
            time.sleep(0.2)
        return payload

    def test_health(self) -> None:
        status, payload = self._request("GET", "/v1/health")
        self.assertEqual(200, status)
        self.assertEqual("ok", payload["status"])

    def test_rejects_bad_token(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._request("GET", "/v1/health", token="wrong")
        self.assertEqual(401, caught.exception.code)

    def test_build_round_trip(self) -> None:
        archive = base64.b64encode(_zip({"build.sh": "#!/bin/sh\necho compiled\n"})).decode()
        status, submitted = self._request(
            "POST",
            "/v1/jobs",
            {
                "project_id": "p1",
                "build_system": "gradle",
                "kind": "BUILD",
                "commands": [["sh", "-c", "cat build.sh && echo done"]],
                "source_archive": archive,
                "artifact_globs": [],
            },
        )
        self.assertEqual(202, status)

        payload = self._await(submitted["job_id"])

        self.assertEqual("succeeded", payload["state"], payload)
        self.assertEqual(0, payload["exit_code"])
        self.assertIn("compiled", payload["log"])
        self.assertIn("done", payload["log"])

    def test_failed_build_reports_reason(self) -> None:
        archive = base64.b64encode(_zip({"a.txt": "x"})).decode()
        _, submitted = self._request(
            "POST",
            "/v1/jobs",
            {
                "project_id": "p2",
                "build_system": "npm",
                "kind": "TEST",
                "commands": [["sh", "-c", "echo failing; exit 1"]],
                "source_archive": archive,
            },
        )

        payload = self._await(submitted["job_id"])

        self.assertEqual("failed", payload["state"])
        self.assertIn("code 1", payload["failure_reason"])

    def test_artifact_download(self) -> None:
        archive = base64.b64encode(_zip({"a.txt": "x"})).decode()
        _, submitted = self._request(
            "POST",
            "/v1/jobs",
            {
                "project_id": "p3",
                "build_system": "gradle",
                "kind": "BUILD",
                "commands": [["sh", "-c", "mkdir -p out && echo payload > out/result.txt"]],
                "source_archive": archive,
                "artifact_globs": ["out/*.txt"],
            },
        )

        payload = self._await(submitted["job_id"])

        self.assertEqual("succeeded", payload["state"])
        artifact = payload["artifacts"][0]

        request = urllib.request.Request(self.base + artifact["url"])
        request.add_header("Authorization", "Bearer secret-token")
        with urllib.request.urlopen(request, timeout=30) as response:
            self.assertEqual(b"payload\n", response.read())

    def test_rejects_missing_archive(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._request("POST", "/v1/jobs", {"project_id": "p4", "commands": [["echo", "hi"]]})
        self.assertEqual(400, caught.exception.code)

    def test_rejects_zip_slip_upload(self) -> None:
        archive = base64.b64encode(_zip({"../evil.txt": "x"})).decode()
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._request(
                "POST",
                "/v1/jobs",
                {"project_id": "p5", "commands": [["echo", "hi"]], "source_archive": archive},
            )
        self.assertEqual(400, caught.exception.code)

    def test_rejects_empty_command_list(self) -> None:
        archive = base64.b64encode(_zip({"a.txt": "x"})).decode()
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._request(
                "POST",
                "/v1/jobs",
                {"project_id": "p6", "commands": [], "source_archive": archive},
            )
        self.assertEqual(400, caught.exception.code)

    def test_cancel_unknown_job_is_404(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._request("POST", "/v1/jobs/nope/cancel", {})
        self.assertEqual(404, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
