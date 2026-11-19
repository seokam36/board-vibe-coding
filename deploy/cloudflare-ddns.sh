#!/bin/bash
# S22: 서버(EC2)가 켜질 때 "내 새 공인 IP"를 Cloudflare DNS의 A 레코드에 적는다.
# 왜: 고정 IP(Elastic IP)를 안 쓰기로 해서(꺼 둬도 월 약 $3.65) EC2를 켤 때마다 공인 IP가 바뀐다.
#     도메인(예: kampus.<도메인>)이 새 IP를 가리키게 고쳐 주면 앱·브라우저·인증서는 이름만 보고 찾아온다.
#
# 서버 위치: ~/board/cloudflare-ddns.sh (권한 700). 실행: crontab의 @reboot 줄 (docs/deploy.md "S22 DNS 자동 고침")
# 필요한 값: ~/board/.env 의 세 줄 (이 파일 전체를 source 하지 않고 이 세 줄만 골라 읽는다)
#   CF_API_TOKEN=   Cloudflare API 토큰 (그 도메인의 "DNS 편집" 권한만). 비밀 값 -> 로그·화면에 절대 안 남긴다
#   CF_ZONE_ID=     도메인 고유 번호(영역 ID, 32글자). 비밀 아님
#   CF_RECORD_NAME= 고칠 이름 전체 (예: kampus.<도메인>). 레코드는 Cloudflare에 미리 만들어 둔다(A, 프록시 끔)
# 쓰는 도구: bash, curl, grep, sed (Amazon Linux 2023 기본). jq는 없을 수 있어 안 쓴다.
# 종료 코드: 0 = 고쳤거나 이미 같음, 1 = 실패 (이유는 로그 한 줄)

set -u                              # 없는 변수를 쓰면 바로 멈춘다 (오타 방지). set -x는 토큰이 찍히므로 절대 쓰지 않는다
ENV_FILE="${ENV_FILE:-$HOME/board/.env}"
API="${CF_API_BASE:-https://api.cloudflare.com/client/v4}"   # 시험 때만 가짜 서버 주소로 바꾼다
IMDS="${IMDS_BASE:-http://169.254.169.254}"                  # EC2 메타데이터(자기 정보) 주소. 시험 때만 바꾼다

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [ddns] $*"; }

# .env에서 "이름=값" 한 줄의 값만 꺼낸다 (줄 끝 \r·앞뒤 공백·따옴표 제거). 여러 줄이면 마지막 줄
read_env() {
  grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '\r' \
    | sed -E -e 's/^[[:space:]]+//' -e 's/[[:space:]]+$//' -e 's/^["'\'']//' -e 's/["'\'']$//'
}

if [ ! -r "$ENV_FILE" ]; then log "실패: $ENV_FILE 을 읽을 수 없음"; exit 1; fi
CF_API_TOKEN="$(read_env CF_API_TOKEN)"
CF_ZONE_ID="$(read_env CF_ZONE_ID)"
CF_RECORD_NAME="$(read_env CF_RECORD_NAME)"
if [ -z "$CF_API_TOKEN" ] || [ -z "$CF_ZONE_ID" ] || [ -z "$CF_RECORD_NAME" ]; then
  log "실패: .env에 CF_API_TOKEN·CF_ZONE_ID·CF_RECORD_NAME 중 빈 값이 있음 (값은 출력하지 않음)"; exit 1
fi
# 영역 ID는 32글자 16진수, 이름은 영문·숫자·점·하이픈만 (주소에 그대로 붙이므로 엉뚱한 글자를 막는다)
if ! [[ "$CF_ZONE_ID" =~ ^[0-9a-f]{32}$ ]]; then log "실패: CF_ZONE_ID 형식이 아님(32글자 16진수)"; exit 1; fi
if ! [[ "$CF_RECORD_NAME" =~ ^[A-Za-z0-9.-]+$ ]]; then log "실패: CF_RECORD_NAME 형식이 아님"; exit 1; fi
# 토큰은 영문·숫자·_·- 만 (붙여넣기 실수로 꺾쇠 <>·따옴표·공백이 섞이면 아래 curl 설정 줄이 깨져 원인 모를 인증 실패가 된다)
if ! [[ "$CF_API_TOKEN" =~ ^[A-Za-z0-9_-]+$ ]]; then log "실패: CF_API_TOKEN 형식이 아님 - 꺾쇠·따옴표·공백이 섞였는지 확인 (값은 출력하지 않음)"; exit 1; fi
NAME_LC="$(tr '[:upper:]' '[:lower:]' <<<"$CF_RECORD_NAME")"   # Cloudflare는 이름을 소문자로 돌려준다 (비교용)

# Cloudflare API 호출. 토큰은 명령줄 인자가 아니라 표준입력(curl -K -)으로 넘겨 ps 목록에도 안 보이게 한다
# 켜진 직후엔 바깥 인터넷·DNS가 아직 덜 붙었을 수 있어 실패하면 5초 뒤 최대 3번 다시 시도한다
# (설정(-K -)은 시작할 때 한 번 읽히므로 재시도해도 토큰은 그대로 쓰인다. 같은 값으로 고치는 거라 여러 번 가도 안전)
cf() {   # 사용: cf 방법 경로 [본문JSON]
  local method="$1" path="$2" body="${3:-}"
  printf 'header = "Authorization: Bearer %s"\n' "$CF_API_TOKEN" | \
    curl -sS --max-time 15 --retry 3 --retry-delay 5 --retry-all-errors -K - -X "$method" \
      -H "Content-Type: application/json" ${body:+--data "$body"} "$API$path"
}

# Cloudflare 응답이 실패일 때 로그 한 줄. 응답이 비었으면(연결 자체 실패) 따로 알린다
cf_fail() {   # 사용: cf_fail 단계이름 응답
  if [ -z "$2" ]; then log "실패: $1 - Cloudflare에 연결 못 함(인터넷·DNS 확인, 다시 시도 3번 뒤)"
  else log "실패: $1 (오류 코드: $(grep -oE '"code": *[0-9]+' <<<"$2" | head -n 3 | tr -d ' "' | tr '\n' ' '))"; fi
}

# IPv4 모양 + 네 칸 모두 0~255인지 (999.1.1.1 같은 값을 Cloudflare에 보내지 않게)
valid_ipv4() {
  [[ "$1" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  local n; for n in "${BASH_REMATCH[@]:1}"; do [ "$((10#$n))" -le 255 ] || return 1; done
}

# 1) 내 공인 IP: EC2 메타데이터(IMDSv2 — 먼저 짧은 표를 받고 그 표로 묻는 방식)
#    켜진 직후엔 네트워크가 아직 안 붙었을 수 있어 10초 간격으로 최대 12번(2분) 다시 묻는다
IP=""
for _ in $(seq 1 12); do
  IMDS_TOKEN="$(curl -sS --max-time 3 -X PUT "$IMDS/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)"
  if [ -n "$IMDS_TOKEN" ]; then
    IP="$(curl -sS --max-time 3 -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" "$IMDS/latest/meta-data/public-ipv4" 2>/dev/null || true)"
  fi
  if valid_ipv4 "$IP"; then break; fi
  IP=""; sleep 10
done
if [ -z "$IP" ]; then log "실패: EC2 메타데이터에서 공인 IP를 못 받음(2분 동안)"; exit 1; fi

# 2) 지금 Cloudflare에 적힌 레코드 찾기 (A 레코드, 이 이름 하나)
RESP="$(cf GET "/zones/$CF_ZONE_ID/dns_records?type=A&name=$CF_RECORD_NAME" || true)"
if ! grep -q '"success": *true' <<<"$RESP"; then cf_fail "레코드 조회 실패" "$RESP"; exit 1; fi
# 레코드 개수: result_info.count. 0개면 먼저 만들어야 하고, 2개 이상이면 어느 것을 고칠지 몰라 멈춘다
COUNT="$(grep -oE '"count": *[0-9]+' <<<"$RESP" | tail -n 1 | grep -oE '[0-9]+$')"
if [ "${COUNT:-}" = "0" ]; then log "실패: Cloudflare에 $CF_RECORD_NAME A 레코드가 없음 (콘솔에서 먼저 만들 것)"; exit 1; fi
if [ "${COUNT:-}" != "1" ]; then log "실패: $CF_RECORD_NAME A 레코드가 1개가 아님(${COUNT:-?}개) - 콘솔에서 하나만 남길 것"; exit 1; fi
# 응답에서 "result":[ 뒤(레코드 배열)만 잘라 첫 레코드의 id·content(지금 IP)를 꺼낸다
# (jq가 없어 grep으로 찾으므로, 앞쪽 다른 객체의 "id"를 잘못 집지 않게 범위를 좁힌다)
RECORDS="$(tr -d '\n' <<<"$RESP" | sed -E 's/.*"result": *\[//')"
RECORD_ID="$(grep -oE '"id": *"[0-9a-f]{32}"' <<<"$RECORDS" | head -n 1 | grep -oE '[0-9a-f]{32}')"
OLD_IP="$(grep -oE '"content": *"[^"]*"' <<<"$RECORDS" | head -n 1 | sed -E 's/.*"([^"]*)"$/\1/')"
GOT_NAME="$(grep -oE '"name": *"[^"]*"' <<<"$RECORDS" | head -n 1 | sed -E 's/.*"([^"]*)"$/\1/' | tr '[:upper:]' '[:lower:]')"
if [ -z "$RECORD_ID" ]; then log "실패: 응답에서 레코드 id를 못 찾음"; exit 1; fi
# 검색 조건이 무시돼 다른 레코드가 왔을 때 덮어쓰지 않게, 찾은 레코드 이름이 정확히 같은지 한 번 더 본다
# (같은 도메인 밑에 다른 앱 이름도 둘 계획이라 중요)
if [ "$GOT_NAME" != "$NAME_LC" ]; then log "실패: 찾은 레코드 이름이 $CF_RECORD_NAME 이 아님(${GOT_NAME:-없음}) - 고치지 않음"; exit 1; fi

if [ "$OLD_IP" = "$IP" ]; then log "그대로: $CF_RECORD_NAME = $IP (바꿀 필요 없음)"; exit 0; fi

# 3) 새 IP로 고치기. proxied:false = 프록시 끔(회색 구름) 유지, ttl 60 = 1분 (다음 변경이 빨리 퍼지게)
#    type·name도 같이 보낸다: 부분 수정(PATCH)은 content만으로 되지만, 어느 쪽으로 해석돼도 통과하게
#    (실제 Cloudflare로는 운영 전환 때 첫 실행이 첫 확인이다 - docs/deploy.md)
RESP="$(cf PATCH "/zones/$CF_ZONE_ID/dns_records/$RECORD_ID" \
  "{\"type\":\"A\",\"name\":\"$NAME_LC\",\"content\":\"$IP\",\"ttl\":60,\"proxied\":false}" || true)"
if ! grep -q '"success": *true' <<<"$RESP"; then cf_fail "레코드 수정 실패" "$RESP"; exit 1; fi
log "고침: $CF_RECORD_NAME ${OLD_IP:-?} -> $IP"
exit 0
