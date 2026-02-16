"""AndroidWorld agent adapter for Agent Relay.

Implements the EnvironmentInteractingAgent interface by sending ADB broadcasts
to Agent Relay's BenchmarkReceiver and polling for results.
"""

import json
import subprocess
import time
import uuid
from typing import Any


class AgentRelayAgent:
    """Agent that delegates to Agent Relay running on an Android device."""

    PACKAGE = "com.agentrelay"
    RECEIVER = f"{PACKAGE}/.benchmark.BenchmarkReceiver"
    RESULT_PATH = "files/benchmark_result.json"

    def __init__(
        self,
        api_key: str,
        model: str = "claude-sonnet-4-5-20250929",
        poll_interval: float = 2.0,
        timeout: float = 300.0,
    ):
        self.api_key = api_key
        self.model = model
        self.poll_interval = poll_interval
        self.timeout = timeout
        self._configure()

    def _adb(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        cmd = ["adb"] + list(args)
        return subprocess.run(cmd, capture_output=True, text=True, check=check)

    def _broadcast(self, action: str, extras: dict[str, str] | None = None) -> None:
        cmd = [
            "shell", "am", "broadcast",
            "-n", f"{self.PACKAGE}/{self.PACKAGE}.benchmark.BenchmarkReceiver",
            "-a", action,
        ]
        if extras:
            for key, value in extras.items():
                cmd.extend(["--es", key, value])
        self._adb(*cmd)

    def _configure(self) -> None:
        self._broadcast(
            "com.agentrelay.benchmark.CONFIGURE",
            {"api_key": self.api_key, "model": self.model},
        )

    def _read_result(self) -> dict[str, Any] | None:
        result = self._adb(
            "shell", "run-as", self.PACKAGE, "cat", self.RESULT_PATH,
            check=False,
        )
        if result.returncode != 0 or not result.stdout.strip():
            return None
        try:
            return json.loads(result.stdout.strip())
        except json.JSONDecodeError:
            return None

    def _delete_result(self) -> None:
        self._adb(
            "shell", "run-as", self.PACKAGE, "rm", "-f", self.RESULT_PATH,
            check=False,
        )

    def step(self, goal: str) -> dict[str, Any]:
        """Execute a single task and return the result."""
        task_id = str(uuid.uuid4())[:8]

        # Clear any previous result
        self._delete_result()

        # Start the task
        self._broadcast(
            "com.agentrelay.benchmark.START_TASK",
            {"task": goal, "task_id": task_id},
        )

        # Poll for result
        start_time = time.time()
        while time.time() - start_time < self.timeout:
            time.sleep(self.poll_interval)
            result = self._read_result()
            if result is not None:
                return result

        return {
            "task_id": task_id,
            "task": goal,
            "status": "timeout",
            "duration_ms": int((time.time() - start_time) * 1000),
            "iterations": -1,
            "final_message": f"Timed out after {self.timeout}s",
        }

    def reset(self) -> None:
        """Stop any running task, clean up, and go home."""
        self._broadcast("com.agentrelay.benchmark.STOP_TASK")
        self._delete_result()
        time.sleep(0.5)
        self._adb("shell", "input", "keyevent", "3")  # HOME
