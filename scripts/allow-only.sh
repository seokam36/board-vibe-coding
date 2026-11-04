#!/bin/bash
# 서브에이전트의 Write/Edit를 지정한 경로에만 허용하는 PreToolUse 훅
# 사용법: ./scripts/allow-only.sh 허용경로1 허용경로2 ...
#   - "/"로 끝나는 인자: 그 폴더 아래 전체 허용 (예: src/test/)
#   - 그 외: 그 파일만 허용 (예: docs/changelog.md)
# Windows(Git Bash)와 macOS/Linux 모두에서 동작하도록 경로 구분자를 /로 통일한다.

shopt -s nocasematch   # 허용 경로 비교도 대소문자를 구분하지 않음

INPUT=$(cat)
FILE=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

# jq가 없거나 입력을 해석하지 못했거나 경로가 없으면 판단할 수 없으므로 차단
if [ $? -ne 0 ] || [ -z "$FILE" ]; then
  echo "Blocked: 입력에서 파일 경로를 읽지 못했습니다 (jq 설치 여부와 입력 형식을 확인하세요)" >&2
  exit 2
fi

# 역슬래시(\)를 슬래시(/)로 통일하고 소문자로 맞춘다 (Windows 경로 대응)
FILE="${FILE//\\//}"
FILE="${FILE,,}"
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
PROJECT_DIR="${PROJECT_DIR//\\//}"
PROJECT_DIR="${PROJECT_DIR,,}"

# 절대경로를 프로젝트 기준 상대경로로 바꾼다
REL="${FILE#"$PROJECT_DIR"/}"

# worktree(.claude/worktrees/<이름>/)에서 작업하면 경로 앞에 이 부분이 붙는다.
# 떼어 내야 "src/test/" 같은 허용 경로와 똑같이 비교할 수 있다.
if [[ "$REL" =~ ^\.claude/worktrees/[^/]+/(.*)$ ]]; then
  REL="${BASH_REMATCH[1]}"
fi

# "../"로 허용 폴더 밖으로 빠져나가는 경로는 막는다
if [[ "$REL" == *"../"* || "$REL" == *"/.." || "$REL" == ".." ]]; then
  echo "Blocked: 상위 폴더(../)를 포함한 경로는 허용하지 않습니다 (시도한 경로: $REL)" >&2
  exit 2
fi

for ALLOWED in "$@"; do
  if [[ "$ALLOWED" == */ ]]; then
    # 폴더 허용: 그 폴더로 시작하면 통과
    if [[ "$REL" == "$ALLOWED"* ]]; then
      exit 0
    fi
  else
    # 파일 허용: 정확히 같은 경로면 통과
    if [[ "$REL" == "$ALLOWED" ]]; then
      exit 0
    fi
  fi
done

echo "Blocked: 이 에이전트는 다음 경로에만 쓸 수 있습니다: $*  (시도한 경로: $REL)" >&2
exit 2
