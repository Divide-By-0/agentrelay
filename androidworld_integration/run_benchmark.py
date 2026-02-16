#!/usr/bin/env python3
"""AndroidWorld benchmark runner for Agent Relay.

Usage:
    python run_benchmark.py --api_key sk-ant-... --tasks all
    python run_benchmark.py --api_key sk-ant-... --tasks ContactsAddContact,ClockSetAlarm
"""

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

from agent_relay_agent import AgentRelayAgent


def verify_installation() -> bool:
    """Check that Agent Relay is installed on the connected device."""
    result = subprocess.run(
        ["adb", "shell", "pm", "list", "packages", "com.agentrelay"],
        capture_output=True, text=True, check=False,
    )
    if "com.agentrelay" not in result.stdout:
        print("ERROR: Agent Relay is not installed. Run setup_emulator.sh first.")
        return False
    return True


def get_available_tasks() -> list[str]:
    """Return the list of AndroidWorld task names.

    When the android_world package is installed, this imports tasks from it.
    Otherwise returns a small default set for smoke-testing.
    """
    try:
        from android_world import task_registry  # type: ignore
        return list(task_registry.TASK_REGISTRY.keys())
    except ImportError:
        # Fallback: common tasks for quick testing without full AndroidWorld
        return [
            "ContactsAddContact",
            "ClockSetAlarm",
            "ClockSetTimer",
            "SettingsToggleWifi",
            "SettingsToggleBluetooth",
        ]


def run_with_android_world(agent: AgentRelayAgent, task_names: list[str]) -> list[dict]:
    """Run tasks using the full AndroidWorld framework (if installed)."""
    try:
        from android_world import task_registry, env as aw_env  # type: ignore
    except ImportError:
        print("android_world package not found. Running in standalone mode.")
        return run_standalone(agent, task_names)

    results = []
    env = aw_env.AndroidWorldEnv()

    for task_name in task_names:
        if task_name not in task_registry.TASK_REGISTRY:
            print(f"  SKIP: Unknown task '{task_name}'")
            results.append({"task": task_name, "status": "skipped", "success": False})
            continue

        print(f"\n--- Task: {task_name} ---")
        task = task_registry.TASK_REGISTRY[task_name](env)

        try:
            # Initialize task state
            task.initialize(env)
            goal = task.goal

            # Run the agent
            agent.reset()
            result = agent.step(goal)
            print(f"  Agent result: {result.get('status', 'unknown')}")

            # Check ground truth
            success = task.is_successful(env)
            print(f"  Ground truth: {'PASS' if success else 'FAIL'}")

            results.append({
                "task": task_name,
                "goal": goal,
                "agent_status": result.get("status"),
                "duration_ms": result.get("duration_ms"),
                "success": success,
            })

        except Exception as e:
            print(f"  ERROR: {e}")
            results.append({
                "task": task_name,
                "status": "error",
                "error": str(e),
                "success": False,
            })
        finally:
            try:
                task.tear_down(env)
            except Exception:
                pass

    return results


def run_standalone(agent: AgentRelayAgent, task_names: list[str]) -> list[dict]:
    """Run tasks without the AndroidWorld framework (just sends goals to agent)."""
    # Map task names to natural language goals for standalone mode
    task_goals = {
        "ContactsAddContact": "Open Contacts and add a new contact named 'Test User' with phone number '555-0100'",
        "ClockSetAlarm": "Open the Clock app and set an alarm for 7:30 AM",
        "ClockSetTimer": "Open the Clock app and set a timer for 5 minutes",
        "SettingsToggleWifi": "Open Settings and toggle WiFi",
        "SettingsToggleBluetooth": "Open Settings and toggle Bluetooth",
    }

    results = []
    for task_name in task_names:
        goal = task_goals.get(task_name, f"Complete the task: {task_name}")
        print(f"\n--- Task: {task_name} ---")
        print(f"  Goal: {goal}")

        agent.reset()
        result = agent.step(goal)
        status = result.get("status", "unknown")
        print(f"  Result: {status} ({result.get('duration_ms', 0)}ms)")
        print(f"  Message: {result.get('final_message', '')}")

        results.append({
            "task": task_name,
            "goal": goal,
            "agent_status": status,
            "duration_ms": result.get("duration_ms"),
            "success": status == "completed",
        })

    return results


def main():
    parser = argparse.ArgumentParser(description="Run AndroidWorld benchmark with Agent Relay")
    parser.add_argument("--api_key", required=True, help="Claude API key")
    parser.add_argument("--model", default="claude-sonnet-4-5-20250929", help="Model to use")
    parser.add_argument("--tasks", default="all", help="Comma-separated task names or 'all'")
    parser.add_argument("--timeout", type=float, default=300.0, help="Per-task timeout in seconds")
    parser.add_argument("--output", default="benchmark_results.json", help="Output file path")
    args = parser.parse_args()

    if not verify_installation():
        sys.exit(1)

    agent = AgentRelayAgent(
        api_key=args.api_key,
        model=args.model,
        timeout=args.timeout,
    )

    available_tasks = get_available_tasks()
    if args.tasks == "all":
        task_names = available_tasks
    else:
        task_names = [t.strip() for t in args.tasks.split(",")]

    print(f"Running {len(task_names)} tasks with model={args.model}")
    start_time = time.time()

    results = run_with_android_world(agent, task_names)

    total_time = time.time() - start_time
    passed = sum(1 for r in results if r.get("success"))
    total = len(results)
    success_rate = (passed / total * 100) if total > 0 else 0

    summary = {
        "model": args.model,
        "total_tasks": total,
        "passed": passed,
        "failed": total - passed,
        "success_rate": round(success_rate, 1),
        "total_time_s": round(total_time, 1),
        "results": results,
    }

    print(f"\n{'='*50}")
    print(f"Results: {passed}/{total} ({success_rate:.1f}%)")
    print(f"Total time: {total_time:.1f}s")

    output_path = Path(args.output)
    output_path.write_text(json.dumps(summary, indent=2))
    print(f"Results saved to {output_path}")


if __name__ == "__main__":
    main()
