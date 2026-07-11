# Task Prompt Convention

클로드 코드에 작업을 넘기는 프롬프트를 작성할 때 지키는 규칙.

## 스코프

- TASKS.md 항목 하나 = 프롬프트 하나. 여러 항목을 한 프롬프트에 묶지 않는다.
- 코드 변경이 있는 항목과 순수 검증(코드 diff 없음) 항목은 항상 분리한다.
  검증 단계는 리뷰(code-review/security-review/ponytail-review) 대상이 아니므로
  구현 항목과 같이 묶으면 리뷰 스코프가 섞인다.

## 내용

- TASKS.md / PLANNING.md에 이미 있는 내용(체크리스트, API 스펙, Key Decisions 등)은
  프롬프트에 다시 풀어쓰지 않는다. "TASKS.md / PLANNING.md 참고"로 대체 — 문서가 바뀌면
  프롬프트만 낡은 정보로 남는 걸 방지.
- PLANNING.md에 이미 결정된 사항(Key Decisions 표 등)은 선택지로 제시하지 않는다.
  진짜 아직 안 정해진 것만 클로드 코드가 옵션을 제시하고 사용자가 고르는 방식으로 진행한다.

## 실행 방식

- 실제 코드 변경이 있는 프롬프트는 "구현 전에 계획부터 제시해줘"를 포함해 plan mode로 진행한다.
- 순수 검증/문서 갱신처럼 리스크가 작은 항목은 plan mode 불필요.
