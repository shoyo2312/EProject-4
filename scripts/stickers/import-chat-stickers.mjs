#!/usr/bin/env node
/**
 * Imports the chat sticker pack into MinIO, once.
 *
 *   node scripts/stickers/import-chat-stickers.mjs            # download, verify, upload what is missing
 *   node scripts/stickers/import-chat-stickers.mjs --verify   # also read every sticker back with no credentials
 *   node scripts/stickers/import-chat-stickers.mjs --fill     # (maintainers) record size/hash for stickers the manifest has not pinned yet
 *
 * The catalog is `services/chat-service/src/main/resources/stickers/fluent-3d-v1.json` — the same
 * file chat-service serves from, so the importer and the backend cannot drift. Images come from
 * Microsoft's fluentui-emoji repository at the commit pinned in that manifest (MIT, see
 * LICENSE-fluentui-emoji.txt, which is uploaded next to the images).
 *
 * Configuration comes from the environment, or from the repository's untracked `.env`:
 *   MINIO_ENDPOINT  where THIS script reaches MinIO (e.g. http://localhost:9000)
 *   MINIO_ACCESS_KEY, MINIO_SECRET_KEY, MINIO_BUCKET
 *   MEDIA_PUBLIC_URL  (only for --verify) what browsers use; defaults to MINIO_ENDPOINT
 * Nothing is hard-coded and no credential is printed.
 *
 * Safe to run again: an object that is already there is compared by SHA-256 and left alone; one that
 * differs from the manifest stops the run, because a published sticker id must never quietly become a
 * different picture. Dependency-free on purpose: Node's fetch and crypto only (S3 Signature V4 below).
 */
import { createHash, createHmac } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const stickerDir = join(root, "services", "chat-service", "src", "main", "resources", "stickers");
const manifestPath = join(stickerDir, "fluent-3d-v1.json");
const args = new Set(process.argv.slice(2));

function loadDotEnv() {
  const file = join(root, ".env");
  if (!existsSync(file)) return {};
  const out = {};
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (match && !line.trim().startsWith("#")) out[match[1]] = match[2].replace(/^(['"])(.*)\1$/, "$2");
  }
  return out;
}

const dotenv = loadDotEnv();
const env = (key) => process.env[key] ?? dotenv[key];

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");
const hmac = (key, data) => createHmac("sha256", key).update(data).digest();

/** PNG: the 8-byte signature, then an IHDR chunk whose width and height are big-endian 32-bit. */
function readPng(buffer, label) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (buffer.length < 24 || !buffer.subarray(0, 8).equals(signature)) {
    throw new Error(`${label}: not a PNG`);
  }
  if (buffer.toString("ascii", 12, 16) !== "IHDR") throw new Error(`${label}: PNG has no IHDR header`);
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

/** S3 Signature V4, path-style, for the three calls this script makes. */
async function s3(method, key, { body, contentType } = {}) {
  const endpoint = env("MINIO_ENDPOINT");
  const accessKey = env("MINIO_ACCESS_KEY");
  const secretKey = env("MINIO_SECRET_KEY");
  const bucket = env("MINIO_BUCKET");
  if (!endpoint || !accessKey || !secretKey || !bucket) {
    throw new Error("MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY and MINIO_BUCKET must be set (environment or .env)");
  }

  const url = new URL(`${endpoint.replace(/\/$/, "")}/${bucket}/${key.split("/").map(encodeURIComponent).join("/")}`);
  const payload = body ?? Buffer.alloc(0);
  const payloadHash = sha256(payload);
  const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, "");
  const date = amzDate.slice(0, 8);
  const headers = { host: url.host, "x-amz-content-sha256": payloadHash, "x-amz-date": amzDate };
  if (contentType) headers["content-type"] = contentType;

  const signedNames = Object.keys(headers).sort();
  const canonicalHeaders = signedNames.map((name) => `${name}:${headers[name]}\n`).join("");
  const canonicalRequest = [method, url.pathname, "", canonicalHeaders, signedNames.join(";"), payloadHash].join("\n");
  const scope = `${date}/us-east-1/s3/aws4_request`;
  const toSign = ["AWS4-HMAC-SHA256", amzDate, scope, sha256(Buffer.from(canonicalRequest))].join("\n");
  const signingKey = hmac(hmac(hmac(hmac(`AWS4${secretKey}`, date), "us-east-1"), "s3"), "aws4_request");
  const signature = createHmac("sha256", signingKey).update(toSign).digest("hex");
  headers.authorization = `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedNames.join(";")}, Signature=${signature}`;

  const response = await fetch(url, { method, headers, body: method === "PUT" ? payload : undefined });
  return { status: response.status, buffer: Buffer.from(await response.arrayBuffer()), headers: response.headers };
}

async function download(manifest, sticker) {
  const path = sticker.sourcePath.split("/").map(encodeURIComponent).join("/");
  const url = `https://raw.githubusercontent.com/microsoft/fluentui-emoji/${manifest.source.commit}/${path}`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${sticker.stickerId}: GitHub answered ${response.status} for ${sticker.sourcePath}`);
  return Buffer.from(await response.arrayBuffer());
}

/** Puts `buffer` at `key` unless it is already there; a different object at the same key is an error. */
async function ensureObject(key, buffer, contentType, label) {
  const head = await s3("GET", key);
  if (head.status === 200) {
    if (sha256(head.buffer) === sha256(buffer)) return "unchanged";
    throw new Error(`${label}: ${key} already holds different bytes. A released sticker id must not change — pick a new id or version.`);
  }
  if (head.status !== 404) throw new Error(`${label}: reading ${key} answered ${head.status}`);

  const put = await s3("PUT", key, { body: buffer, contentType });
  if (put.status !== 200) throw new Error(`${label}: uploading ${key} answered ${put.status}`);
  return "uploaded";
}

const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const stickers = manifest.stickers;
if (stickers.length !== 24) throw new Error(`expected 24 stickers, the manifest lists ${stickers.length}`);

const fetched = new Map();
let manifestChanged = false;
for (const sticker of stickers) {
  const buffer = await download(manifest, sticker);
  const { width, height } = readPng(buffer, sticker.stickerId);
  const digest = sha256(buffer);

  if (!sticker.sha256) {
    if (!args.has("--fill")) throw new Error(`${sticker.stickerId}: the manifest has no sha256 yet — run with --fill once and commit the result`);
    Object.assign(sticker, { width, height, bytes: buffer.length, sha256: digest });
    manifestChanged = true;
  } else if (sticker.sha256 !== digest || sticker.width !== width || sticker.height !== height) {
    throw new Error(`${sticker.stickerId}: the download no longer matches the pinned hash — refusing to continue`);
  }
  fetched.set(sticker.stickerId, buffer);
}

if (manifestChanged) {
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + "\n");
  console.log("manifest filled with size and SHA-256 for", stickers.length, "stickers");
}
if (args.has("--fill") && !args.has("--upload")) {
  console.log("downloaded and verified", stickers.length, "PNG files; nothing uploaded (--fill only)");
  process.exit(0);
}

const results = { uploaded: 0, unchanged: 0 };
for (const sticker of stickers) {
  const outcome = await ensureObject(sticker.objectKey, fetched.get(sticker.stickerId), "image/png", sticker.stickerId);
  results[outcome] += 1;
}
const licenseText = readFileSync(join(stickerDir, manifest.source.licenseFile));
const licenseKey = `${manifest.objectPrefix}LICENSE.txt`;
results[await ensureObject(licenseKey, licenseText, "text/plain; charset=utf-8", "license")] += 1;
console.log(`MinIO bucket ${env("MINIO_BUCKET")}: ${results.uploaded} uploaded, ${results.unchanged} already present (${stickers.length} images + license)`);

if (args.has("--verify")) {
  const base = (env("MEDIA_PUBLIC_URL") ?? env("MINIO_ENDPOINT")).replace(/\/$/, "");
  let bad = 0;
  for (const sticker of stickers) {
    const response = await fetch(`${base}/${env("MINIO_BUCKET")}/${sticker.objectKey}`); // no credentials
    const body = Buffer.from(await response.arrayBuffer());
    const ok = response.status === 200 && (response.headers.get("content-type") ?? "").startsWith("image/png") && sha256(body) === sticker.sha256;
    if (!ok) {
      bad += 1;
      console.error(`  not publicly readable as expected: ${sticker.stickerId} (status ${response.status}, ${response.headers.get("content-type")})`);
    }
  }
  if (bad > 0) {
    console.error(`${bad} of ${stickers.length} stickers cannot be read anonymously — media-worker applies the read policy on startup (BucketInitializer).`);
    process.exit(2);
  }
  console.log(`verified: all ${stickers.length} stickers are readable without credentials at ${base}`);
}
