# Review Workflow

## Review Output Logging
`/ponytail-review`, `security-review`, `/code-review` 실행 후 결과를 `.claude/reviews/` 아래
새 파일로 저장 (예: `.claude/reviews/2026-07-10-security-review.md`).
이 폴더는 gitignore 처리 — 로컬 참고용, 저장소 이력에는 안 남음.

## Review vs Fix
리뷰 스킬(code-review/security-review/ponytail-review) 실행 시 findings만 보고하고 종료한다.
발견된 이슈의 수정은 사용자가 별도 turn에서 명시적으로 지시했을 때만 진행한다.

## Commit Message Style
형식: `<날짜>: <한 줄 요약> (<phase/task 참조>)`
"왜"가 diff만 봐서 안 보일 때만 한 줄 본문 추가. diff 내용을 줄줄이 재서술하지 않음.

예시:
```
2026-07-10: tool-level 에러 핸들링 추가 (Phase 3 #2)

MCP 경계로 raw exception이 넘어가지 않게 방어
```
