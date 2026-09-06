import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";

export function verifySignerOutput(output, expectedFingerprint) {
  assert.match(expectedFingerprint, /^[a-f0-9]{64}$/, "Invalid pinned signer fingerprint");
  const signers = [...output.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$/gm)];
  assert.equal(signers.length, 1, "Public APK must have exactly one verified signer");
  assert.equal(signers[0][1].toLowerCase(), expectedFingerprint, "APK signing identity changed; restore the fixed key, do not publish");
}

export function verifyPublicApk(apkPath, expectedFingerprint) {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  assert.ok(sdk, "Set ANDROID_HOME to the Android SDK before building a public download");
  const java = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
    : "java";
  const verifier = path.join(sdk, "build-tools", "36.0.0", "lib", "apksigner.jar");
  const result = spawnSync(java, ["-jar", verifier, "verify", "--print-certs", path.resolve(apkPath)], {
    encoding: "utf8", timeout: 60000, windowsHide: true,
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, "APK signature verification failed: " + (result.stderr || "unknown verifier error"));
  verifySignerOutput(result.stdout, expectedFingerprint);
}
