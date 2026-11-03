# 무료 LLM 선택과 요약 설정

2026-11-03 공식 문서 확인 기준, 기본값을 Groq Free Plan에서 사용할 수 있는 `openai/gpt-oss-120b`로 선택했다. 목표는 한국어 회의 요약의 속도와 내용 보존의 균형이다. 모든 무료 모델보다 빠르고 정확하다는 주장은 아니며, 이 프로젝트 데이터로 품질·지연 시간을 측정한 결과도 아니다.

| 후보 | 제공 방식 | 공식 안내 속도 | 선택 판단 |
| --- | --- | --- | --- |
| GPT-OSS 120B | Groq, Production | 약 500 tokens/s | 기본값. 속도와 모델 규모를 함께 고려 |
| GPT-OSS 20B | Groq, Production | 약 1,000 tokens/s | 속도를 더 우선할 때 `LLM_MODEL=openai/gpt-oss-20b`로 변경 가능 |
| Qwen 3.8 27B | Groq, Preview | 약 450 tokens/s | Preview 모델의 수명·변경 가능성을 고려해 기본값에서 제외 |
| GLM-4.7-Flash | Z.ai | 비교 가능한 속도 수치 미확인 | 이전 설정을 명시적으로 선택할 수 있도록 지원 |

속도 수치는 [Groq 모델 안내](https://console.groq.com/docs/models)의 생성 처리량이며, 전체 요청 시간이나 무료 계정의 응답 시간을 보장하지 않는다. 한국어 회의 요약 정확도를 같은 조건에서 비교한 검증 자료가 없어 순위를 단정하지 않았다.

[Groq 무료 한도](https://console.groq.com/docs/rate-limits)는 해당 모델에 기본 30 RPM, 1,000 RPD, 8,000 TPM, 200,000 TPD를 안내한다. 계정별 한도는 콘솔이 우선한다. **Free Plan 한도 안에서 사용한다는 전제이며 유료 계정의 과금을 앱이 차단하지는 않는다.** [Z.ai 가격표](https://docs.z.ai/guides/overview/pricing)는 GLM-4.7-Flash를 무료로 표시한다.

## 연결

[Groq 콘솔](https://console.groq.com/keys)에서 키를 발급한 후 프로젝트 루트 `.env`에 넣고 서버를 재시작한다. 키를 채팅이나 Git에 올리지 않는다.

```properties
LLM_PROVIDER=groq
GROQ_API_KEY=YOUR_GROQ_KEY
LLM_TIMEOUT_SECONDS=90
LLM_MAX_OUTPUT_TOKENS=2048
```

주소와 모델은 기본값을 사용하므로 적지 않아도 된다. 필요하면 `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`로 명시한다. `LLM_PROVIDER=glm`이면 Z.ai 주소, `glm-4.7-flash`, `GLM_API_KEY`를 사용한다. 자체 호환 서버는 `LLM_PROVIDER=compatible`과 명시적 주소·모델을 설정한다. 이 모드에서는 키가 없어도 된다.

Groq 모드에서는 기존 GLM/EXAONE 키와 주소를 읽지 않는다. 다른 제공업체에 기존 키가 전송되는 일을 피하기 위해서다. GLM 또는 compatible 모드를 명시하면 해당 제공업체의 이전 EXAONE 환경 변수도 지원한다. 예전 `GLM_TIMEOUT`, `EXAONE_TIMEOUT` 대신 `LLM_TIMEOUT_SECONDS`를 사용한다.

## 요청·실패 처리

- GPT-OSS 요청은 [Groq 추론 규격](https://console.groq.com/docs/reasoning)에 따라 `reasoning_effort=low`, `include_reasoning=false`, `max_completion_tokens=2048`로 보낸다. 최종 답변만 저장한다.
- 전사를 3,000자 단위로 나눈다. 부분 요약을 다시 통합할 때도 같은 입력 크기를 유지하고 최대 5회 통합 단계로 제한한다. 이는 문자 기준이므로 실제 토큰 제한을 대체하지는 않는다.
- 요약은 핵심 요약·결정 사항·할 일·추가 확인으로 작성한다. 녹취에 없는 담당자, 기한, 수치나 결정을 추측하지 않도록 요청한다. 원문 대조는 여전히 필요하다.
- 요청당 기본 90초를 적용한다. 429 응답이 짧은 `Retry-After`(최대 60초)를 제공할 때만 같은 요청을 한 번 재시도하며, 대기와 재시도도 이 시간 예산 안에서 수행한다. 유료 모델이나 다른 제공업체로 자동 전환하지 않는다.
- 인증, 모델 없음, 무료 한도, 응답 시간 초과를 구분한다. 실패 시 저장된 전사는 보존하며 요약만 재시도할 수 있다. 긴 전사는 무료 요청·토큰 한도에 더 쉽게 도달한다.
- `GET /api/settings/summary`는 제공업체·모델·키 설정 여부·제한 시간만 반환한다. 키 값은 반환하지 않으며 `configured`는 실제 API 연결 검증 결과가 아니다.

API 키가 없는 환경에서는 프로토콜과 실패 처리를 로컬 모의 서버로 검증한다. 실제 속도와 한국어 요약 품질은 발급된 무료 키로 동일한 회의 전사에 대해 측정해야 한다. Whisper STT·GCS 등 기존 서비스의 비용은 LLM 무료 한도와 별개다.
