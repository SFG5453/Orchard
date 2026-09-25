#!/usr/bin/env python3
"""Run one isolated LiteRT GPU option probe with adb GPU-memory sampling.

Requires the debug app/test APKs and the model/input/reference staged under
/data/local/tmp/orchard-gpu-options. It never starts the canary app and stops
only the debug probe if GPU memory or available RAM crosses a safety limit.
"""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path


PACKAGE = "dev.sfg.orchard.mobile.debug"
CANARY = "dev.sfg.orchard.mobile.canary"
ROOT = "/data/local/tmp/orchard-gpu-options"


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True).strip()


def gpu_bytes(pid: str) -> tuple[int, int]:
    report = adb("shell", "dumpsys", "gpu")
    global_bytes = int(re.search(r"Global total: (\d+)", report).group(1))
    match = re.search(rf"Proc {re.escape(pid)} total: (\d+)", report) if pid else None
    return global_bytes, int(match.group(1)) if match else 0


def memory_kib(pid: str) -> tuple[int | None, int]:
    info = adb("shell", "dumpsys", "meminfo", pid) if pid else ""
    pss = re.search(r"TOTAL PSS:\s*(\d+)", info)
    status = adb("shell", "cat", "/proc/meminfo")
    available = int(re.search(r"^MemAvailable:\s*(\d+)", status, re.M).group(1))
    return int(pss.group(1)) if pss else None, available


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name")
    parser.add_argument("--backend", default="OPENCL")
    parser.add_argument("--buffer-storage-type")
    parser.add_argument("--prefer-texture-weights", choices=("true", "false"))
    parser.add_argument("--constant-tensor-sharing", choices=("true", "false"))
    parser.add_argument("--cache-key")
    parser.add_argument("--planner", action="store_true",
                        help="Run the real two-song planner test with the app's GPU runner")
    parser.add_argument("--short-model", help="Staged alternative model for the planner probe")
    parser.add_argument("--short-frames", type=int, help="Frame count of the alternative model")
    parser.add_argument("--model", default=f"{ROOT}/final0_1500_fp16_gpu.tflite")
    parser.add_argument("--input", default=f"{ROOT}/illegal_tail_chunk.f32")
    parser.add_argument("--reference", default=f"{ROOT}/illegal_tail_reference.f32")
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/litert-gpu-options"))
    parser.add_argument("--max-global-gpu-mib", type=int, default=1300)
    parser.add_argument("--min-available-mib", type=int, default=1200)
    parser.add_argument("--timeout-seconds", type=int, default=90)
    args = parser.parse_args()

    if subprocess.run(["adb", "shell", "pidof", CANARY], capture_output=True,
                      text=True).stdout.strip():
        parser.error("Canary is running; do not overlap GPU probes with playback")
    baseline_global, _ = gpu_bytes("")
    if baseline_global > 600 * 1024 * 1024:
        parser.error(f"GPU already uses {baseline_global / 1048576:.1f} MiB")

    command = ["adb", "shell", "am", "instrument", "-w", "-r"]
    if args.planner:
        command.extend(("-e", "class", "dev.sfg.orchard.mobile.playback.smart.V3PlannerInputDeviceTest",
                        "-e", "pinkPantheressParity", "true", "-e", "measureMemory", "true"))
        if args.short_model:
            if not args.short_frames:
                parser.error("--short-frames is required with --short-model")
            command.extend(("-e", "beatShortGpuModelPath", args.short_model,
                            "-e", "beatShortFrames", str(args.short_frames)))
    else:
        command.extend(("-e", "class", "dev.sfg.orchard.mobile.playback.smart.BeatFp16GpuProbeDeviceTest",
                        "-e", "beatFp16Probe", "true", "-e", "beatModelPath", args.model,
                        "-e", "beatInputPath", args.input, "-e", "beatReferencePath", args.reference,
                        "-e", "beatGpuPrecision", "FP16", "-e", "beatGpuBackend", args.backend,
                        "-e", "beatMeasureMemory", "true", "-e", "beatMaxMae", "0.2",
                        "-e", "beatMinPeakMatchRate", "0.95"))
        for flag, value in (("beatGpuBufferStorageType", args.buffer_storage_type),
                            ("beatGpuPreferTextureWeights", args.prefer_texture_weights),
                            ("beatGpuConstantTensorSharing", args.constant_tensor_sharing),
                            ("beatGpuCacheKey", args.cache_key)):
            if value is not None:
                command.extend(("-e", flag, value))
    command.append(f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    samples = []
    pid = ""
    aborted = None
    start = time.monotonic()
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True) as process:
        while process.poll() is None:
            probe_pids = subprocess.run(["adb", "shell", "pidof", PACKAGE],
                                        capture_output=True, text=True).stdout.split()
            probe_pid = probe_pids[0] if probe_pids else ""
            pid = probe_pid or pid
            global_bytes, process_bytes = gpu_bytes(probe_pid)
            pss, available = memory_kib(probe_pid)
            samples.append({"seconds": round(time.monotonic() - start, 3),
                            "globalGpuBytes": global_bytes, "processGpuBytes": process_bytes,
                            "pssKiB": pss, "availableKiB": available})
            if (global_bytes > args.max_global_gpu_mib * 1024 * 1024
                    or available < args.min_available_mib * 1024):
                aborted = "GPU or available RAM safety limit"
            if time.monotonic() - start > args.timeout_seconds:
                aborted = f"{args.timeout_seconds}-second timeout"
            if aborted:
                subprocess.run(["adb", "shell", "am", "force-stop", PACKAGE], check=False)
                break
            time.sleep(0.4)
        try:
            output, _ = process.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            process.terminate()
            output, _ = process.communicate(timeout=5)
    tags = ("OrchardBeatTracker:D", "OrchardTrackAnalyzer:D", "V3PlannerInputDeviceTest:I") \
        if args.planner else ("BeatFp16GpuProbe:I",)
    logcat = adb("logcat", "-d", "--pid", pid, "-s", *tags, "*:S") if pid else ""
    (args.output_dir / f"{args.name}.instrumentation.log").write_text(output)
    (args.output_dir / f"{args.name}.logcat.log").write_text(logcat + "\n")
    report = {
        "name": args.name,
        "backend": args.backend,
        "bufferStorageType": args.buffer_storage_type,
        "preferTextureWeights": args.prefer_texture_weights,
        "constantTensorSharing": args.constant_tensor_sharing,
        "cacheKey": args.cache_key,
        "pid": pid,
        "passed": aborted is None and "OK (1 test)" in output,
        "aborted": aborted,
        "durationSeconds": round(time.monotonic() - start, 3),
        "peakProcessGpuMiB": round(max((s["processGpuBytes"] for s in samples), default=0) / 1048576, 1),
        "peakGlobalGpuMiB": round(max((s["globalGpuBytes"] for s in samples), default=0) / 1048576, 1),
        "peakPssMiB": round(max((s["pssKiB"] for s in samples if s["pssKiB"] is not None), default=0) / 1024, 1),
        "minAvailableMiB": round(min((s["availableKiB"] for s in samples), default=0) / 1024, 1),
        "samples": samples,
    }
    (args.output_dir / f"{args.name}.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "samples"}, indent=2))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
