#!/usr/bin/env node
// Đưa các file trong ./videos lên hệ thống qua đúng luồng upload của app:
//   1. POST /api/v1/videos/upload-url  -> xin policy upload
//   2. POST form multipart lên MinIO   -> lưu file raw
//   3. POST /api/v1/videos             -> tạo bản ghi Video (Mongo)
// Sau đó media-worker tự transcode -> kiểm duyệt -> PUBLISHED -> lên feed/recommendation.
//
// Cách chạy (cần docker-compose + các service đang chạy):
//   SEED_ACCOUNTS="user1:Pass@123,user2:Pass@123" node scripts/seed-videos.mjs
//
// Biến môi trường:
//   SEED_TOKENS    access token (cho tài khoản Google/Facebook), cách nhau bằng dấu phẩy.
//   SEED_ACCOUNTS  hoặc dùng cái này. Danh sách "usernameOrEmail:password", cách nhau bằng dấu phẩy.
//                  Tài khoản phải đã verify email. Mỗi tài khoản chỉ đăng được 10 video/giờ,
//                  nên 20 video cần ít nhất 2 tài khoản (hoặc tắt giới hạn, xem README dưới).
//   GATEWAY_URL    mặc định http://localhost:8080
//   VIDEOS_DIR     mặc định ./videos (tính từ thư mục EProject-4)
//
// Tiêu đề/mô tả/tag lấy từ videos/videos.json nếu có (key = tên file), không thì dùng "Video N".
// File đã đăng xong được ghi vào videos/.seeded.json nên chạy lại sẽ bỏ qua, không đăng trùng.

import { readdir, readFile, writeFile } from "node:fs/promises";
import { openAsBlob, existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const GATEWAY = (process.env.GATEWAY_URL || "http://localhost:8080").replace(/\/$/, "");
const VIDEOS_DIR = path.resolve(ROOT, process.env.VIDEOS_DIR || "videos");
const META_FILE = path.join(VIDEOS_DIR, "videos.json");
const STATE_FILE = path.join(VIDEOS_DIR, ".seeded.json");

const CONTENT_TYPES = { ".mp4": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm" };

function parseAccounts() {
  // Tài khoản Google/Facebook không có mật khẩu: dán access token lấy từ web
  // (DevTools > Application > Local Storage > tiktok.accessToken). Token sống 15 phút.
  const tokens = (process.env.SEED_TOKENS || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((t, i) => ({ login: `token#${i + 1}`, password: null, token: t, fixedToken: true, limited: false }));

  const raw = process.env.SEED_ACCOUNTS || "";
  const accounts = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const i = s.indexOf(":");
      return { login: s.slice(0, i), password: s.slice(i + 1), token: null, tokenAt: 0, limited: false };
    })
    .filter((a) => a.login && a.password);
  const all = [...tokens, ...accounts];
  if (all.length === 0) {
    console.error('Thiếu SEED_ACCOUNTS hoặc SEED_TOKENS. Ví dụ: SEED_ACCOUNTS="user1:Pass@123" hoặc SEED_TOKENS="eyJ..."');
    process.exit(1);
  }
  return all;
}

async function readJson(file, fallback) {
  if (!existsSync(file)) return fallback;
  return JSON.parse(await readFile(file, "utf8"));
}

class ApiError extends Error {
  constructor(status, body, retryAfter) {
    super(`${status} ${body?.code || ""} ${body?.message || ""}`.trim());
    this.status = status;
    this.code = body?.code;
    this.retryAfter = retryAfter;
  }
}

async function api(method, url, { token, body } = {}) {
  const res = await fetch(GATEWAY + url, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => null);
  if (!res.ok || json?.success === false) {
    throw new ApiError(res.status, json, res.headers.get("retry-after"));
  }
  return json.data;
}

// Access token sống 15 phút; đăng nhập lại sau 10 phút cho chắc.
async function tokenFor(acc) {
  if (acc.fixedToken) return acc.token;
  if (acc.token && Date.now() - acc.tokenAt < 10 * 60 * 1000) return acc.token;
  const data = await api("POST", "/api/v1/auth/login", {
    body: { usernameOrEmail: acc.login, password: acc.password },
  });
  acc.token = data.accessToken;
  acc.tokenAt = Date.now();
  return acc.token;
}

async function uploadFile(token, filePath, contentType) {
  const { uploadUrl, formFields, fileUrl } = await api("POST", "/api/v1/videos/upload-url", {
    token,
    body: { contentType },
  });
  const form = new FormData();
  for (const [k, v] of Object.entries(formFields)) form.append(k, v);
  // MinIO bắt buộc "file" là field cuối cùng.
  form.append("file", await openAsBlob(filePath, { type: contentType }), path.basename(filePath));
  const res = await fetch(uploadUrl, { method: "POST", body: form });
  if (res.status !== 204 && res.status !== 201 && res.status !== 200) {
    throw new Error(`Upload lên MinIO lỗi ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  return fileUrl;
}

function naturalSort(a, b) {
  return a.localeCompare(b, undefined, { numeric: true });
}

async function main() {
  const accounts = parseAccounts();
  const meta = await readJson(META_FILE, {});
  const state = await readJson(STATE_FILE, {});

  const files = (await readdir(VIDEOS_DIR))
    .filter((f) => CONTENT_TYPES[path.extname(f).toLowerCase()])
    .sort(naturalSort);

  const todo = files.filter((f) => !state[f]?.videoId);
  console.log(`Tìm thấy ${files.length} video, cần đăng ${todo.length}. Gateway: ${GATEWAY}`);

  let next = 0;
  for (const [i, file] of todo.entries()) {
    const filePath = path.join(VIDEOS_DIR, file);
    const contentType = CONTENT_TYPES[path.extname(file).toLowerCase()];
    const m = meta[file] || {};
    const title = m.title || `Video ${path.basename(file, path.extname(file)).replace(/\D+/g, "") || i + 1}`;

    let done = false;
    for (let tries = 0; tries < accounts.length && !done; tries++) {
      const acc = accounts[next % accounts.length];
      next++;
      if (acc.limited) continue;
      try {
        const token = await tokenFor(acc);
        // Nếu lần trước đã upload file nhưng tạo video lỗi, dùng lại rawFileUrl cũ (cùng tài khoản).
        let rawFileUrl = state[file]?.account === acc.login ? state[file]?.rawFileUrl : null;
        if (!rawFileUrl) {
          process.stdout.write(`[${file}] upload bằng ${acc.login}... `);
          rawFileUrl = await uploadFile(token, filePath, contentType);
          state[file] = { account: acc.login, rawFileUrl };
          await writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        }
        const video = await api("POST", "/api/v1/videos", {
          token,
          body: {
            title,
            description: m.description || null,
            rawFileUrl,
            visibility: "PUBLIC",
            tags: m.tags || [],
          },
        });
        state[file] = { account: acc.login, rawFileUrl, videoId: video.id };
        await writeFile(STATE_FILE, JSON.stringify(state, null, 2));
        console.log(`OK -> id ${video.id} (${video.status})`);
        done = true;
        // SEED_DELAY_MS gives media-worker time to transcode before the next upload
        // lands, so a small dev machine is not doing everything at once.
        const delay = Number(process.env.SEED_DELAY_MS || 0);
        if (delay > 0) await new Promise((r) => setTimeout(r, delay));
      } catch (e) {
        if (e instanceof ApiError && e.status === 429) {
          console.log(`\n  ${acc.login} hết lượt đăng (thử lại sau ${e.retryAfter ?? "?"}s), đổi tài khoản.`);
          acc.limited = true;
        } else if (e instanceof ApiError && e.status === 401 && acc.fixedToken) {
          console.log(`\n  ${acc.login} đã hết hạn (15 phút). Copy token mới từ trình duyệt rồi chạy lại.`);
          acc.limited = true;
        } else if (e instanceof ApiError && e.code === "RAW_FILE_ALREADY_PUBLISHED") {
          console.log("đã đăng từ trước, bỏ qua.");
          state[file] = { ...state[file], videoId: "unknown" };
          await writeFile(STATE_FILE, JSON.stringify(state, null, 2));
          done = true;
        } else {
          console.log(`\n  Lỗi với ${file}: ${e.message}`);
          break; // lỗi không phải do giới hạn -> sang file khác
        }
      }
    }
    if (!done && accounts.every((a) => a.limited)) {
      console.log("\nMọi tài khoản đều hết lượt. Chạy lại sau ~1 giờ, script sẽ tiếp tục từ chỗ dừng.");
      break;
    }
  }

  const uploaded = Object.values(state).filter((s) => s.videoId).length;
  console.log(`\nXong: ${uploaded}/${files.length} video đã đăng.`);
  console.log("Video cần vài giây tới vài phút để transcode + kiểm duyệt rồi mới hiện trên feed.");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
