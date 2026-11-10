#!/usr/bin/env bash
# Kịch bản demo chức năng authentication (auth-service), gọi qua api-gateway.
# Mỗi phần đi từ các ca FAIL (hệ thống chặn đúng) rồi mới đến ca SUCCESS.
#
# Chuẩn bị (mỗi lệnh một terminal):
#   make infra-up
#   make run-gateway
#   make run-auth TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA 2>&1 | tee auth.log
#     1x000...AA là secret test của Cloudflare Turnstile: luôn chấp nhận token giả, nên curl
#     gọi được các API có Turnstile. OTP_LOG_TO_CONSOLE=true (.env) để OTP được in ra log.
#
# Chạy:
#   AUTH_LOG=auth.log scripts/demo-auth.sh
#
# Biến môi trường:
#   BASE_URL         mặc định http://localhost:8080 (gateway)
#   AUTH_LOG         log của auth-service để tự đọc OTP; bỏ trống thì script hỏi nhập tay
#   ADMIN_EMAIL      mặc định admin@tiktok-clone.dev; ADMIN_PASSWORD bỏ trống thì script hỏi
#   A_EMAIL, B_EMAIL email thật (vd. ten+demo1@gmail.com) nếu muốn nhận OTP qua hộp thư
#   AUTO=1           chạy liền một mạch, không dừng giữa các phần

set -uo pipefail

BASE="${BASE_URL:-http://localhost:8080}/api/v1/auth"
TS="XXXX.DUMMY.TOKEN.XXXX"
RUN=$(date +%s); RUN=${RUN: -6}

A_USER="demo_a$RUN"; A_EMAIL="${A_EMAIL:-demo.a.$RUN@example.com}"
A_PASS="Passw0rd#A"; A_NEW_PASS="NewPassw0rd#A"
B_USER="demo_b$RUN"; B_EMAIL="${B_EMAIL:-demo.b.$RUN@example.com}"; B_PASS="Passw0rd#B"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@tiktok-clone.dev}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"

CYAN=$'\033[1;36m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
TOTAL=0; PASSED=0; FAILED=(); BODY=""

json() { jq -nc '$ARGS.named' "$@"; }
j() { jq -r "$1" <<<"$BODY"; }

section() {
  printf '\n%s━━━━━━━━ %s ━━━━━━━━%s\n' "$CYAN" "$1" "$OFF"
  [[ ${AUTO:-0} == 1 ]] || read -rp "  (Enter để chạy phần này) " _
}

note() { printf '  %s» %s%s\n' "$YELLOW" "$1" "$OFF"; }

# call <mong đợi: STATUS hoặc STATUS/ERROR_CODE> <tiêu đề> <METHOD> <path> [body JSON] [access token]
call() {
  local want=$1 title=$2 method=$3 path=$4 body=${5:-} token=${6:-}
  local want_status=${want%%/*} want_code="" label
  [[ $want == */* ]] && want_code=${want#*/}
  if (( want_status >= 400 )); then label="${RED}phải bị chặn: $want${OFF}"; else label="${GREEN}phải thành công: $want${OFF}"; fi

  TOTAL=$((TOTAL + 1))
  printf '\n▶ %s  [%s]\n' "$title" "$label"
  printf '  %s%s /api/v1/auth%s%s\n' "$DIM" "$method" "$path" "$OFF"
  # Mật khẩu admin không hiện lên màn hình chiếu.
  [[ -n $body ]] && printf '  %sbody: %s%s\n' "$DIM" \
    "$(jq -c --arg pw "$ADMIN_PASSWORD" 'map_values(if . == $pw then "******" else . end)' <<<"$body")" "$OFF"

  local args=(-s -m 20 -X "$method" -H 'Content-Type: application/json' -w $'\n%{http_code}')
  [[ -n $body ]] && args+=(-d "$body")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  local out status code
  out=$(curl "${args[@]}" "$BASE$path")
  status=${out##*$'\n'}
  BODY=${out%$'\n'*}
  code=$(jq -r '.code // empty' <<<"$BODY" 2>/dev/null)

  if [[ $status == "$want_status" && ( -z $want_code || $code == "$want_code" ) ]]; then
    PASSED=$((PASSED + 1)); printf '  %s✔ %s %s%s\n' "$GREEN" "$status" "$code" "$OFF"
  else
    FAILED+=("$title (nhận $status $code)"); printf '  %s✘ nhận %s %s, mong đợi %s%s\n' "$RED" "$status" "$code" "$want" "$OFF"
  fi
  # Rút gọn chuỗi dài (JWT) cho dễ nhìn.
  if [[ -n $BODY ]]; then
    jq -C 'walk(if type == "string" and length > 60 then .[0:40] + "…" else . end)' <<<"$BODY" 2>/dev/null \
      | sed 's/^/    /' || printf '    %s\n' "$BODY"
  else
    printf '    %s(body rỗng)%s\n' "$DIM" "$OFF"
  fi
}

# Số dòng OTP <purpose> cho <email> đã có trong log — ghi lại trước khi gửi để không đọc nhầm mã cũ.
otp_count() { if [[ -n ${AUTH_LOG:-} ]]; then grep -cF "$1 for $2 = " "$AUTH_LOG"; else echo 0; fi; }

# get_otp <purpose> <email> <số dòng trước khi gửi>
get_otp() {
  local code="" _
  if [[ -n ${AUTH_LOG:-} ]]; then
    for _ in {1..30}; do
      if (( $(otp_count "$1" "$2") > $3 )); then
        code=$(grep -F "$1 for $2 = " "$AUTH_LOG" | tail -1 | sed -E 's/.* = ([0-9]{6}).*/\1/')
        break
      fi
      sleep 0.5
    done
  fi
  [[ -n $code ]] || read -rp "  Nhập mã OTP vừa gửi tới $2: " code
  printf '  %s» OTP %s gửi tới %s = %s%s\n' "$YELLOW" "$1" "$2" "$code" "$OFF" >&2
  echo "$code"
}

b64url_decode() { local s; s=$(tr '_-' '/+' <<<"$1"); while (( ${#s} % 4 )); do s+="="; done; base64 -d <<<"$s"; }
b64url_encode() { base64 | tr '+/' '-_' | tr -d '=\n'; }

# ─── Kiểm tra môi trường ──────────────────────────────────────────────
probe=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$BASE/login" -H 'Content-Type: application/json' -d '{}')
if [[ $probe != 400 ]]; then
  echo "${RED}Không gọi được $BASE/login (HTTP $probe). Bật gateway + auth-service trước.${OFF}"; exit 1
fi
[[ -n ${AUTH_LOG:-} && ! -f $AUTH_LOG ]] && { echo "${RED}Không thấy file log $AUTH_LOG${OFF}"; exit 1; }
[[ -n ${ADMIN_PASSWORD:-} ]] || { read -rsp "Mật khẩu admin ($ADMIN_EMAIL): " ADMIN_PASSWORD; echo; }
echo "User A: $A_USER / $A_EMAIL    User B: $B_USER / $B_EMAIL"

# ─── 1 ────────────────────────────────────────────────────────────────
section "1. ĐĂNG KÝ TÀI KHOẢN (POST /register)"
call 403/TURNSTILE_VERIFICATION_FAILED "1.1 Đăng ký không có Turnstile token (chống bot)" POST /register \
  "$(json --arg username "$A_USER" --arg email "$A_EMAIL" --arg password "$A_PASS")"
call 400/VALIDATION_ERROR "1.2 Dữ liệu sai: username viết hoa, email sai định dạng, mật khẩu < 8 ký tự" POST /register \
  "$(json --arg username "Bad Name" --arg email "not-an-email" --arg password "123" --arg turnstileToken "$TS")"
n=$(otp_count "verification OTP" "$A_EMAIL")
call 201 "1.3 Đăng ký hợp lệ → tài khoản tạo ra, emailVerified=false, OTP gửi qua email" POST /register \
  "$(json --arg username "$A_USER" --arg email "$A_EMAIL" --arg password "$A_PASS" --arg turnstileToken "$TS")"
A_ID=$(j .data.id)
call 409/USERNAME_ALREADY_EXISTS "1.4 Đăng ký trùng username" POST /register \
  "$(json --arg username "$A_USER" --arg email "other.$A_EMAIL" --arg password "$A_PASS" --arg turnstileToken "$TS")"
call 409/EMAIL_ALREADY_EXISTS "1.5 Đăng ký trùng email (không phân biệt hoa thường)" POST /register \
  "$(json --arg username "${A_USER}x" --arg email "$(tr '[:lower:]' '[:upper:]' <<<"$A_EMAIL")" --arg password "$A_PASS" --arg turnstileToken "$TS")"

# ─── 2 ────────────────────────────────────────────────────────────────
section "2. ĐĂNG NHẬP & XÁC THỰC EMAIL (POST /login, /verify-email)"
call 401/INVALID_CREDENTIALS "2.1 Sai mật khẩu" POST /login \
  "$(json --arg usernameOrEmail "$A_USER" --arg password "wrong-password")"
call 401/INVALID_CREDENTIALS "2.2 Tài khoản không tồn tại → cùng một lỗi, không lộ tài khoản nào có thật" POST /login \
  "$(json --arg usernameOrEmail "ghost_$RUN" --arg password "wrong-password")"
call 403/EMAIL_NOT_VERIFIED "2.3 Đúng mật khẩu nhưng email chưa xác thực" POST /login \
  "$(json --arg usernameOrEmail "$A_USER" --arg password "$A_PASS")"
call 400/INVALID_OTP "2.4 Xác thực email bằng OTP sai" POST /verify-email \
  "$(json --arg email "$A_EMAIL" --arg otp "000000")"
OTP=$(get_otp "verification OTP" "$A_EMAIL" "$n")
call 200 "2.5 Xác thực email bằng OTP đúng" POST /verify-email \
  "$(json --arg email "$A_EMAIL" --arg otp "$OTP")"
call 200 "2.6 Đăng nhập bằng username → accessToken (15 phút) + refreshToken (7 ngày)" POST /login \
  "$(json --arg usernameOrEmail "$A_USER" --arg password "$A_PASS")"
ACCESS=$(j .data.accessToken); REFRESH=$(j .data.refreshToken)

# ─── 3 ────────────────────────────────────────────────────────────────
section "3. GỌI API CẦN ĐĂNG NHẬP BẰNG JWT (GET /me)"
call 403 "3.1 Không gửi token" GET /me
IFS=. read -r jwt_h jwt_p jwt_s <<<"$ACCESS"
note "Payload thật của access token: $(b64url_decode "$jwt_p")"
FORGED="$jwt_h.$(b64url_decode "$jwt_p" | sed 's/"role":"USER"/"role":"ADMIN"/' | b64url_encode).$jwt_s"
note "Kẻ gian sửa payload thành role ADMIN nhưng giữ nguyên chữ ký cũ"
call 401/UNAUTHORIZED "3.2 Token bị sửa (chữ ký không khớp)" GET /me "" "$FORGED"
call 200 "3.3 Token hợp lệ → trả về thông tin user" GET /me "" "$ACCESS"

# ─── 4 ────────────────────────────────────────────────────────────────
section "4. REFRESH TOKEN XOAY VÒNG & PHÁT HIỆN TOKEN BỊ ĐÁNH CẮP (POST /refresh)"
call 401/INVALID_REFRESH_TOKEN "4.1 Refresh bằng chuỗi giả" POST /refresh \
  "$(json --arg refreshToken "abc.def.ghi")"
call 401/INVALID_REFRESH_TOKEN "4.2 Refresh bằng accessToken (sai loại token)" POST /refresh \
  "$(json --arg refreshToken "$ACCESS")"
OLD_REFRESH=$REFRESH
call 200 "4.3 Refresh hợp lệ → cặp token mới, refresh token cũ hết hiệu lực" POST /refresh \
  "$(json --arg refreshToken "$OLD_REFRESH")"
ACCESS=$(j .data.accessToken); REFRESH=$(j .data.refreshToken)
note "Giả lập kẻ trộm dùng lại refresh token cũ sau 11 giây (quá thời gian ân hạn 10 giây)…"
sleep 11
call 401/INVALID_REFRESH_TOKEN "4.4 Dùng lại refresh token cũ → coi là bị đánh cắp, thu hồi MỌI phiên" POST /refresh \
  "$(json --arg refreshToken "$OLD_REFRESH")"
call 401/INVALID_REFRESH_TOKEN "4.5 Refresh token mới của chính chủ cũng bị thu hồi theo" POST /refresh \
  "$(json --arg refreshToken "$REFRESH")"
call 401/UNAUTHORIZED "4.6 Access token đang dùng cũng bị thu hồi" GET /me "" "$ACCESS"
call 200 "4.7 Chính chủ đăng nhập lại (bằng email)" POST /login \
  "$(json --arg usernameOrEmail "$A_EMAIL" --arg password "$A_PASS")"
ACCESS=$(j .data.accessToken); REFRESH=$(j .data.refreshToken)

# ─── 5 ────────────────────────────────────────────────────────────────
section "5. ĐĂNG XUẤT (POST /logout)"
call 200 "5.1 Đăng xuất: thu hồi refresh token + đưa access token vào blacklist" POST /logout \
  "$(json --arg refreshToken "$REFRESH")" "$ACCESS"
call 401/UNAUTHORIZED "5.2 Dùng lại access token sau khi đăng xuất" GET /me "" "$ACCESS"
call 401/INVALID_REFRESH_TOKEN "5.3 Dùng lại refresh token sau khi đăng xuất" POST /refresh \
  "$(json --arg refreshToken "$REFRESH")"
call 200 "5.4 Đăng nhập lại" POST /login \
  "$(json --arg usernameOrEmail "$A_EMAIL" --arg password "$A_PASS")"
ACCESS=$(j .data.accessToken); REFRESH=$(j .data.refreshToken)

# ─── 6 ────────────────────────────────────────────────────────────────
section "6. QUÊN MẬT KHẨU (POST /forgot-password, /reset-password/verify, /reset-password/complete)"
call 403/TURNSTILE_VERIFICATION_FAILED "6.1 Yêu cầu reset không có Turnstile" POST /forgot-password \
  "$(json --arg email "$A_EMAIL")"
call 200 "6.2 Email không tồn tại → vẫn 200 (không lộ email nào đã đăng ký)" POST /forgot-password \
  "$(json --arg email "nobody.$RUN@example.com" --arg turnstileToken "$TS")"
n=$(otp_count "password reset OTP" "$A_EMAIL")
call 200 "6.3 Email thật → OTP reset gửi qua email" POST /forgot-password \
  "$(json --arg email "$A_EMAIL" --arg turnstileToken "$TS")"
call 400/INVALID_OTP "6.4 Nhập OTP sai" POST /reset-password/verify \
  "$(json --arg email "$A_EMAIL" --arg otp "000000")"
OTP=$(get_otp "password reset OTP" "$A_EMAIL" "$n")
call 200 "6.5 Nhập OTP đúng → nhận resetToken (15 phút)" POST /reset-password/verify \
  "$(json --arg email "$A_EMAIL" --arg otp "$OTP")"
RESET=$(j .data.resetToken)
call 200 "6.6 Đặt mật khẩu mới" POST /reset-password/complete \
  "$(json --arg resetToken "$RESET" --arg newPassword "$A_NEW_PASS")"
call 400/RESET_SESSION_EXPIRED "6.7 Dùng lại resetToken lần 2 → chỉ dùng được 1 lần" POST /reset-password/complete \
  "$(json --arg resetToken "$RESET" --arg newPassword "Another#Pass1")"
call 401/UNAUTHORIZED "6.8 Phiên đăng nhập trước khi đổi mật khẩu bị thu hồi" GET /me "" "$ACCESS"
call 401/INVALID_CREDENTIALS "6.9 Đăng nhập bằng mật khẩu CŨ" POST /login \
  "$(json --arg usernameOrEmail "$A_EMAIL" --arg password "$A_PASS")"
call 200 "6.10 Đăng nhập bằng mật khẩu MỚI" POST /login \
  "$(json --arg usernameOrEmail "$A_EMAIL" --arg password "$A_NEW_PASS")"
ACCESS=$(j .data.accessToken); REFRESH=$(j .data.refreshToken)

# ─── 7 ────────────────────────────────────────────────────────────────
section "7. ĐỔI USERNAME (PUT /me/username)"
call 400/VALIDATION_ERROR "7.1 Username sai định dạng (chữ hoa, khoảng trắng)" PUT /me/username \
  "$(json --arg username "Bad Name")" "$ACCESS"
call 409/USERNAME_ALREADY_EXISTS "7.2 Username đã có người dùng" PUT /me/username \
  "$(json --arg username "$ADMIN_USERNAME")" "$ACCESS"
call 200 "7.3 Đổi username hợp lệ" PUT /me/username \
  "$(json --arg username "${A_USER}_new")" "$ACCESS"
call 429/USERNAME_CHANGE_TOO_SOON "7.4 Đổi tiếp ngay → phải chờ 30 ngày" PUT /me/username \
  "$(json --arg username "${A_USER}_again")" "$ACCESS"

# ─── 8 ────────────────────────────────────────────────────────────────
section "8. VÔ HIỆU HOÁ & MỞ LẠI TÀI KHOẢN (POST /account/deactivate, /login reactivate)"
call 403/INVALID_CREDENTIALS "8.1 Vô hiệu hoá với mật khẩu sai" POST /account/deactivate \
  "$(json --arg password "wrong-password")" "$ACCESS"
call 200 "8.2 Vô hiệu hoá với mật khẩu đúng → mọi phiên kết thúc" POST /account/deactivate \
  "$(json --arg password "$A_NEW_PASS")" "$ACCESS"
call 403/ACCOUNT_DEACTIVATED "8.3 Đăng nhập bình thường → bị chặn vì tài khoản đã vô hiệu hoá" POST /login \
  "$(json --arg usernameOrEmail "$A_EMAIL" --arg password "$A_NEW_PASS")"
call 200 "8.4 Đăng nhập kèm reactivate=true → mở lại tài khoản" POST /login \
  "$(json --arg usernameOrEmail "$A_EMAIL" --arg password "$A_NEW_PASS" --argjson reactivate true)"
ACCESS=$(j .data.accessToken)

# ─── 9 ────────────────────────────────────────────────────────────────
section "9. ĐĂNG KÝ KIỂU EMAIL-FIRST CỦA WEB (POST /register/start, /verify, /complete)"
call 403/TURNSTILE_VERIFICATION_FAILED "9.1 Bắt đầu đăng ký không có Turnstile" POST /register/start \
  "$(json --arg email "$B_EMAIL")"
call 409/EMAIL_ALREADY_EXISTS "9.2 Email đã có tài khoản (của user A)" POST /register/start \
  "$(json --arg email "$A_EMAIL" --arg turnstileToken "$TS")"
n=$(otp_count "verification OTP" "$B_EMAIL")
call 200 "9.3 Email mới → OTP gửi qua email (chưa tạo tài khoản)" POST /register/start \
  "$(json --arg email "$B_EMAIL" --arg turnstileToken "$TS")"
call 400/INVALID_OTP "9.4 Nhập OTP sai" POST /register/verify \
  "$(json --arg email "$B_EMAIL" --arg otp "000000")"
OTP=$(get_otp "verification OTP" "$B_EMAIL" "$n")
call 200 "9.5 Nhập OTP đúng → nhận signupToken (30 phút)" POST /register/verify \
  "$(json --arg email "$B_EMAIL" --arg otp "$OTP")"
SIGNUP=$(j .data.signupToken)
call 409/USERNAME_ALREADY_EXISTS "9.6 Chọn username đã có người dùng → signupToken vẫn còn dùng được" POST /register/complete \
  "$(json --arg signupToken "$SIGNUP" --arg username "$ADMIN_USERNAME" --arg password "$B_PASS")"
call 201 "9.7 Hoàn tất → tạo tài khoản đã xác thực email và đăng nhập luôn" POST /register/complete \
  "$(json --arg signupToken "$SIGNUP" --arg username "$B_USER" --arg password "$B_PASS")"
call 400/SIGNUP_SESSION_EXPIRED "9.8 Dùng lại signupToken → chỉ dùng được 1 lần" POST /register/complete \
  "$(json --arg signupToken "$SIGNUP" --arg username "${B_USER}x" --arg password "$B_PASS")"

# ─── 10 ───────────────────────────────────────────────────────────────
section "10. CHỐNG DÒ MẬT KHẨU (khoá sau 5 lần sai trong 15 phút) — user B"
for i in 1 2 3 4 5; do
  call 401/INVALID_CREDENTIALS "10.$i Sai mật khẩu lần $i" POST /login \
    "$(json --arg usernameOrEmail "$B_USER" --arg password "guess-$i")"
done
call 429/TOO_MANY_LOGIN_ATTEMPTS "10.6 Lần 6 nhập ĐÚNG mật khẩu vẫn bị khoá" POST /login \
  "$(json --arg usernameOrEmail "$B_USER" --arg password "$B_PASS")"

# ─── 11 ───────────────────────────────────────────────────────────────
section "11. PHÂN QUYỀN ADMIN & XÁC THỰC 2 LỚP (POST /login, /login/otp, GET /admin/**)"
call 403 "11.1 User thường gọi API admin" GET "/admin/users?q=demo_" "" "$ACCESS"
call 401/INVALID_CREDENTIALS "11.2 Admin sai mật khẩu" POST /login \
  "$(json --arg usernameOrEmail "$ADMIN_EMAIL" --arg password "wrong-password")"
call 403/TURNSTILE_VERIFICATION_FAILED "11.3 Admin đúng mật khẩu nhưng thiếu Turnstile" POST /login \
  "$(json --arg usernameOrEmail "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD")"
n=$(otp_count "admin login OTP" "$ADMIN_EMAIL")
call 401/MFA_REQUIRED "11.4 Đúng mật khẩu + Turnstile → vẫn chưa vào được, cần OTP lớp 2 qua email" POST /login \
  "$(json --arg usernameOrEmail "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" --arg turnstileToken "$TS")"
call 400/INVALID_OTP "11.5 Nhập OTP lớp 2 sai" POST /login/otp \
  "$(json --arg usernameOrEmail "$ADMIN_EMAIL" --arg otp "000000" --arg turnstileToken "$TS")"
OTP=$(get_otp "admin login OTP" "$ADMIN_EMAIL" "$n")
call 200 "11.6 Nhập OTP đúng + ghi nhớ thiết bị → token admin + deviceToken" POST /login/otp \
  "$(json --arg usernameOrEmail "$ADMIN_EMAIL" --arg otp "$OTP" --arg turnstileToken "$TS" --argjson rememberDevice true)"
ADMIN_ACCESS=$(j .data.accessToken); DEVICE=$(j .data.deviceToken)
call 200 "11.7 Admin tìm user demo" GET "/admin/users?q=demo_" "" "$ADMIN_ACCESS"
call 200 "11.8 Admin xem nhật ký bảo mật của user A" GET "/admin/users/$A_ID/security-events?size=50" "" "$ADMIN_ACCESS"
note "Các sự kiện đã ghi lại cho user A: $(j '[.data.content[]?.eventType] | reverse | join(" → ")')"
call 200 "11.9 Admin đăng nhập lại trên thiết bị đã ghi nhớ → bỏ qua bước OTP" POST /login \
  "$(json --arg usernameOrEmail "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" --arg turnstileToken "$TS" --arg deviceToken "$DEVICE")"

# ─── Tổng kết ─────────────────────────────────────────────────────────
printf '\n%s━━━━━━━━ KẾT QUẢ: %d/%d bước đúng như mong đợi ━━━━━━━━%s\n' "$CYAN" "$PASSED" "$TOTAL" "$OFF"
# ${arr[@]+...}: bash 3.2 của macOS coi mảng rỗng là unbound dưới set -u.
for f in ${FAILED[@]+"${FAILED[@]}"}; do printf '  %s✘ %s%s\n' "$RED" "$f" "$OFF"; done
(( ${#FAILED[@]} == 0 ))
