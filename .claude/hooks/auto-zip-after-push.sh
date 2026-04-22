#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# auto-zip-after-push.sh — git push 성공 후 ApiViewer 자동 압축
# ═══════════════════════════════════════════════════════════════
# 호출: Claude Code PostToolUse (matcher=Bash) 훅에서 stdin 으로
#   {"tool_name":"Bash","tool_input":{"command":"..."},...} JSON 수신.
#
# 동작:
#  1) 명령 내 'git push' 포함 여부 확인 (단어 경계 regex — false-positive 차단)
#  2) 미포함이면 silent exit
#  3) 포함이면 /Users/baegmyeongseon/LP_DEV/ApiViewer 를 CLAUDE.md 규칙에 맞춰 압축
#     - 출력: /Users/baegmyeongseon/Downloads/ApiViewer.zip (기존 삭제 후 재생성)
#     - 제외: target/ .git/ .idea/ .claude/ data/ logs/ *.sh *.bat mvnw mvnw.cmd
#             *.jar lib/*.jar application.properties repos-config.yml
#  4) application.properties 가 최근 10커밋 내 변경되었으면 경고 문구만 표시 (제외는 그대로)
#  5) 결과를 {"systemMessage":..., "suppressOutput":true} JSON 으로 출력
# ═══════════════════════════════════════════════════════════════

payload=$(cat)
cmd=$(echo "$payload" | jq -r '.tool_input.command // ""')

# 단어 경계 regex — 'git push' 가 독립 토큰으로 있는 경우만 매칭
if ! echo "$cmd" | grep -qE '(^|[[:space:];&|])git[[:space:]]+push([[:space:]]|$)'; then
  exit 0
fi

PROJ=/Users/baegmyeongseon/LP_DEV/ApiViewer
OUT=/Users/baegmyeongseon/Downloads/ApiViewer.zip

if [ ! -d "$PROJ" ]; then
  jq -nc '{systemMessage:"[auto-zip] ApiViewer 경로 없음 — 압축 건너뜀", suppressOutput:true}'
  exit 0
fi

# application.properties 최근 10커밋 변경 체크 (경고만, 제외는 그대로)
PROP_WARN=""
if git -C "$PROJ" log --oneline HEAD~10..HEAD -- src/main/resources/application.properties 2>/dev/null | grep -q .; then
  PROP_WARN=" ⚠ application.properties 최근 변경됨 — 포함 여부 확인 필요 (현재 제외)"
fi

rm -f "$OUT"

cd /Users/baegmyeongseon/LP_DEV || {
  jq -nc '{systemMessage:"[auto-zip] LP_DEV 접근 실패", suppressOutput:true}'
  exit 0
}

if ! zip -rq "$OUT" ApiViewer \
  --exclude "ApiViewer/target/*" \
  --exclude "ApiViewer/.git/*" \
  --exclude "ApiViewer/.idea/*" \
  --exclude "ApiViewer/.claude/*" \
  --exclude "ApiViewer/data/*" \
  --exclude "ApiViewer/logs/*" \
  --exclude "ApiViewer/*.sh" \
  --exclude "ApiViewer/*.bat" \
  --exclude "ApiViewer/mvnw" \
  --exclude "ApiViewer/mvnw.cmd" \
  --exclude "ApiViewer/*.jar" \
  --exclude "ApiViewer/lib/*.jar" \
  --exclude "ApiViewer/src/main/resources/application.properties" \
  --exclude "ApiViewer/application.properties" \
  --exclude "ApiViewer/repos-config.yml" \
  --exclude "ApiViewer/src/main/resources/repos-config.yml" 2>&1; then
  jq -nc '{systemMessage:"[auto-zip] 압축 실패", suppressOutput:true}'
  exit 0
fi

SIZE=$(ls -lh "$OUT" 2>/dev/null | awk '{print $5}')
MSG="[auto-zip] 압축 완료 → $OUT ($SIZE)$PROP_WARN"
jq -nc --arg m "$MSG" '{systemMessage:$m, suppressOutput:true}'
