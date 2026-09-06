import assert from "node:assert/strict";
import test from "node:test";
import { verifySignerOutput } from "./verify-apk.mjs";

const fingerprint = "6278edd3637cf54377d63f78f7134ac1ab6e4b5b3721229719201e8262ab3215";
const signer = (value, index = 1) => `Signer #${index} certificate SHA-256 digest: ${value}\n`;

test("the pinned public signer is accepted", () => {
  verifySignerOutput(signer(fingerprint), fingerprint);
  verifySignerOutput(signer(fingerprint.toUpperCase()), fingerprint);
});
test("a different machine's signing key is rejected", () => {
  assert.throws(() => verifySignerOutput(signer("a".repeat(64)), fingerprint), /identity changed/);
});
test("unsigned or multi-signer packages are rejected", () => {
  assert.throws(() => verifySignerOutput("", fingerprint), /exactly one/);
  assert.throws(() => verifySignerOutput(signer(fingerprint) + signer(fingerprint, 2), fingerprint), /exactly one/);
});
test("invalid release metadata is rejected", () => {
  assert.throws(() => verifySignerOutput(signer(fingerprint), ""), /Invalid pinned/);
});
