# 보이스 노트

회의, 강의, 인터뷰의 음성을 텍스트와 요약으로 정리하고, 나중에 전사 내용으로 다시 찾을 수 있는 개인 학습용 음성 노트입니다. 브라우저에서 직접 녹음하거나 기존 음성 파일을 올리면 한국어 전사, 화자 구분, AI 요약을 하나의 노트에서 확인할 수 있습니다.

Spring Boot 3.5 / Java 17로 서버를 구성하고 Google Cloud, EXAONE, MariaDB, Elasticsearch를 연결합니다.

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 브라우저 녹음 | 마이크로 음성을 녹음하고 WebM 파일로 업로드합니다. 마이크 권한과 WebM 녹음을 지원하는 브라우저가 필요하며, localhost 또는 HTTPS에서 사용할 수 있습니다. |
| 음성 파일 업로드 | WebM, WAV, MP3, FLAC, OGG 형식의 30MB 이하 파일을 받습니다. 제목은 최대 120자이며, 비워 두면 파일 이름을 사용합니다. |
| 한국어 전사와 화자 구분 | Google Speech-to-Text V2의 Chirp 3 모델로 음성을 한국어 텍스트로 변환하고 화자별 발언을 구분합니다. 화자 라벨은 발언자를 구별하는 표시이며 실제 이름을 식별하지는 않습니다. |
| AI 요약 | EXAONE에 전사 내용을 보내 화자별 발언, 핵심 내용, 결정 사항, 할 일을 정리하도록 요청합니다. 긴 녹취는 12,000자씩 나누어 요약한 뒤 하나의 요약으로 통합합니다. |
| 노트 목록과 상세 조회 | 저장된 노트를 생성일 기준 최신순으로 보여 줍니다. 노트 상세 화면에서 제목, 생성 시간, 처리 상태, AI 요약, 전체 전사를 확인할 수 있습니다. |
| 원본 음성 재생 | 노트에 저장된 원본 음성을 브라우저 오디오 플레이어로 재생해 전사 내용과 비교할 수 있습니다. |
| 전사 내용 검색 | 검색어로 Elasticsearch에 저장된 전사 본문을 검색해 관련 노트를 찾습니다. 검색 대상은 전사 내용입니다. |
| 오류 확인과 재처리 | 처리가 실패하면 오류를 표시하고 **다시 처리하기** 버튼으로 재시도할 수 있습니다. 전사 저장이 끝난 뒤 요약에 실패했다면 저장된 전사를 재사용합니다. |

## 사용 방법

1. 아래 실행 안내에 따라 서버와 필요한 서비스를 준비한 뒤 `http://localhost:8080`에 접속합니다.
2. **새 노트** 화면에서 제목을 입력하고 **파일 선택**으로 음성을 고르거나 **녹음 시작**으로 녹음합니다. 녹음을 마치면 **녹음 종료**를 누릅니다.
3. **전사 및 요약 시작**을 누르면 음성을 업로드하고 전사와 요약을 순서대로 실행합니다. 완료까지 걸리는 시간은 음성 길이와 외부 API 처리 시간에 따라 달라집니다.
4. 노트 상세 화면에서 원본 음성, AI 요약, 전체 전사를 확인합니다. 실패한 노트는 오류 내용을 확인한 뒤 **다시 처리하기**를 누릅니다.
5. 왼쪽 노트 목록에서 이전 기록을 열거나 **전사 내용 검색**에 검색어를 입력해 관련 노트를 찾습니다.

## 처리 흐름

```mermaid
flowchart LR
    A[녹음 또는 파일 선택] --> B[GCS에 원본 음성 저장]
    B --> C[Speech-to-Text 전사와 화자 구분]
    C --> D[Elasticsearch에 전사 저장]
    D --> E[EXAONE 요약]
    E --> F[MariaDB에 요약과 완료 상태 저장]
    F --> G[노트 조회와 음성 재생]
```

노트 정보와 처리 상태는 MariaDB에 저장됩니다. 화면에서는 다음 상태로 진행 상황을 표시합니다.

| 상태 | 화면 표시 | 의미 |
| --- | --- | --- |
| `UPLOADED` | 업로드 완료 | 원본 음성과 노트 정보가 저장됐습니다. |
| `PROCESSING` | 처리 중 | 전사와 요약을 처리하고 있습니다. |
| `READY` | 완료 | 전사와 요약을 확인할 수 있습니다. |
| `FAILED` | 처리 실패 | 처리 중 오류가 발생했으며 재시도할 수 있습니다. |

## 기술 구성

| 역할 | 구성 |
| --- | --- |
| 음성 원본 저장 | Google Cloud Storage |
| 한국어 전사·화자 분리 | Google Speech-to-Text V2, Chirp 3 (`us`, `ko-KR`) |
| 회의 요약 | 기존 EXAONE Chat Completions API |
| 노트 정보·처리 상태·요약 저장 | 기존 MariaDB의 `voice_notes` 테이블 |
| 화자별 전사 저장·본문 검색 | 로컬 Docker Elasticsearch, `voice-note-segments` 인덱스 |

## 실행

Java 17, Docker, 접속 가능한 MariaDB를 준비합니다. MariaDB 서버·데이터베이스·계정은 기존 것을 사용하며 이 프로젝트에서 만들지 않습니다.

```powershell
if (-not (Test-Path application-local.yml)) { Copy-Item application-local.yml.example application-local.yml }
# application-local.yml의 DB, GCP, EXAONE 값을 입력합니다.
docker compose up -d elasticsearch
.\gradlew.bat bootRun
```

`http://localhost:8080`에서 녹음하거나 음성을 업로드합니다. 녹음은 localhost 또는 HTTPS에서 마이크 권한을 허용해야 동작합니다. WebM, WAV, MP3, FLAC, OGG 형식의 30MB 이하 파일을 받습니다.

`application-local.yml`은 프로젝트 루트에서 실행할 때 자동 로드되며 Git·GCP 업로드 제외 목록에 포함됩니다. 배포에서는 이 파일 없이 환경 변수로 설정할 수 있습니다. 로컬 파일에 직접 지정한 값은 `application.yml`의 환경 변수 기본 설정보다 우선하므로, 둘 중 한 방식을 사용하세요.

## EXAONE API 연결

2026-10-04 확인 기준 최신 범용 EXAONE 언어 모델인 **K-EXAONE 2.0**을 기본 모델명으로 지정했습니다. [LG AI Research 공식 저장소](https://github.com/LG-AI-EXAONE/K-EXAONE-2.0)와 [공식 모델 카드](https://huggingface.co/LGAI-EXAONE/K-EXAONE-2.0-750B-A37B)의 Chat Completions 예제를 기준으로 구성했습니다. 모델은 원격 서버에서 실행하며 가중치를 다운로드하지 않습니다.

```yaml
voice-notes:
  exaone:
    base-url: https://YOUR_API_HOST/v1
    api-key: YOUR_API_KEY
    model: LGAI-EXAONE/K-EXAONE-2.0-750B-A37B
```

주소는 `/chat/completions` 앞까지 넣습니다. 서버가 `K-EXAONE-2.0-750B-A37B`처럼 다른 별칭으로 모델을 제공하면 `model`을 그 이름으로 바꿉니다. 모델명 설정만으로 API 제공업체에 없는 모델이 설치되거나 제공되지는 않습니다.

**API 주소를 얻는 방법:** EXAONE 공개 저장소는 모델과 서버 실행 방법을 제공하며, 바로 사용할 수 있는 공용 API 주소나 키를 발급하지 않습니다. 이미 운영 중인 API가 있다면 운영자/제공업체의 콘솔에서 Chat Completions 엔드포인트와 API 키를 확인하세요. 예를 들어 엔드포인트가 `https://example.com/v1/chat/completions`이면 `base-url`은 `https://example.com/v1`입니다. 서버에 등록된 모델명은 제공업체 문서나 다음 조회 결과의 `data[].id`로 확인할 수 있습니다(서버가 `/models`를 제공하는 경우).

[FriendliAI](https://learn.friendli.ai/articles/4239526803-q6-1-where-do-i-find-or-create-my-api-key)에서 키를 발급하는 경우, Model APIs에 현재 제공되는 EXAONE 모델은 `https://api.friendli.ai/serverless/v1`과 해당 모델 ID를 사용합니다. 현재 기본 모델인 K-EXAONE 2.0은 [Dedicated Endpoint 모델](https://friendli.ai/models/LGAI-EXAONE/K-EXAONE-2.0-750B-A37B)이므로 엔드포인트를 배포한 뒤 `https://api.friendli.ai/dedicated/v1`과 엔드포인트 ID를 사용해야 합니다.

```powershell
$base = 'https://YOUR_API_HOST/v1'
$headers = @{ Authorization = "Bearer $env:EXAONE_API_KEY" }
(Invoke-RestMethod -Uri "$base/models" -Headers $headers).data.id
```

API 키는 환경 변수로 입력하거나 `application-local.yml`에 직접 넣고, 터미널 출력이나 채팅에 붙여넣지 마세요. 키가 필요 없는 자체 서버라면 위 조회 명령에서 `-Headers $headers`를 빼면 됩니다. 자체 서버를 운영한다면 [LG AI Research의 K-EXAONE 2.0 서빙 안내](https://github.com/LG-AI-EXAONE/K-EXAONE-2.0#serving-k-exaone-20)에 따라 vLLM/SGLang을 띄우고 서버 주소의 `/v1`을 사용합니다. 공식 750B 모델 서빙 예시는 H200 GPU 16개를 사용하므로, 서버가 없다면 먼저 제공업체나 별도 추론 환경이 필요합니다.

- 요청: `POST {base-url}/chat/completions`, JSON `model`, `messages`, `stream: false`, `max_tokens`, `temperature`, `top_p`.
- 인증: 키가 있으면 `Authorization: Bearer ...`.
- 응답: `choices[0].message.content`를 최종 요약으로 사용합니다. 빈 응답과 `finish_reason: length`인 잘린 요약은 실패로 처리합니다.
- 기본값은 `chat_template_kwargs.enable_thinking: false`입니다. 게이트웨이가 확장 필드를 받지 않으면 YAML의 `voice-notes.exaone.include-chat-template-kwargs`를 `false`로 바꿉니다.
- 긴 녹취는 12,000자씩 요약한 뒤 통합합니다. 요약 실패 후 재시도하면 ES에 저장된 전사를 재사용합니다.

위 형식과 호환되는 API는 설정만 채우면 연결됩니다. 다른 인증·요청·응답 규격은 `ExaoneApiClient.java`에서 맞춰야 합니다.

## MariaDB와 Elasticsearch

```yaml
spring:
  datasource:
    url: jdbc:mariadb://DB_HOST:3306/DB_NAME
    username: DB_USER
    password: DB_PASSWORD
```

시작할 때 `src/main/resources/db/schema-mariadb.sql`의 `CREATE TABLE IF NOT EXISTS voice_notes`를 실행하고 Hibernate가 스키마를 검증합니다. 기존 테이블을 삭제하거나 수정하지 않습니다. 앱 계정에는 해당 테이블의 읽기·쓰기 및 최초 생성 권한이 필요합니다. 생성 권한이 없으면 관리자가 SQL을 한 번 실행하고 `spring.sql.init.mode=never`로 설정합니다. 기존 `voice_notes` 테이블이 다른 구조라면 앱 전용 데이터베이스를 지정하세요.

기본 Compose는 **Elasticsearch만** 실행합니다. 포트는 `127.0.0.1:9202`, 데이터는 Docker 볼륨에 보관합니다. 인증을 끈 로컬 개발 구성입니다. 기본 텍스트 분석기를 사용하므로 한국어 형태소 검색은 별도 설정이 필요합니다.

## GCP 설정

결제가 연결된 GCP 프로젝트 ID를 준비하세요. 기본 버킷 이름은 `<PROJECT_ID>-voice-notes-audio`이며, 전역에서 중복되면 `-BucketName`으로 다른 이름을 지정합니다. 버킷은 `us-central1`에 생성되고 Speech-to-Text는 `us` 위치를 사용하므로 음성은 미국에 저장·처리됩니다.

GCP 설정 스크립트는 Speech-to-Text, Cloud Storage, Cloud Resource Manager API와 버킷을 준비합니다. 기존 버킷이 보이면 다시 만들지 않습니다. 이후 Application Default Credentials(ADC)에 쿼터 프로젝트를 설정합니다.

```powershell
gcloud auth login
.\scripts\setup-gcp.ps1 -ProjectId YOUR_PROJECT_ID
gcloud auth application-default login
gcloud auth application-default set-quota-project YOUR_PROJECT_ID
```

다른 프로젝트나 저장 위치를 원하면 스크립트의 `-ProjectId`, `-BucketName`, `-Location` 인자로 지정하고 `application-local.yml`의 프로젝트·버킷 이름도 같은 값으로 바꾸세요. [Cloud Storage 버킷 생성 문서](https://docs.cloud.google.com/storage/docs/creating-buckets)를 참고하세요.

```yaml
voice-notes:
  project-id: YOUR_PROJECT_ID
  bucket: YOUR_BUCKET_NAME
```

실행 계정에 Cloud Speech Client와 해당 버킷의 객체 읽기·쓰기 권한이 필요합니다. Speech-to-Text 서비스 에이전트도 음성 객체를 읽을 수 있어야 합니다. [Chirp 3 공식 문서](https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3)를 기준으로 `us-speech.googleapis.com`에 배치 전사를 요청하고 화자 라벨을 구간별로 저장합니다. 화자 번호는 발언 구분이며 실제 인물의 신원을 식별하지 않습니다.

ADC에 설정한 쿼터 프로젝트 헤더도 요청에 전달합니다. 해당 프로젝트에서 `serviceusage.services.use` 권한이 필요합니다([GCP 쿼터 프로젝트 문서](https://docs.cloud.google.com/docs/quotas/set-quota-project)). 배치 결과는 현재 `inlineResult.transcript`와 이전 `transcript` 필드를 모두 지원합니다([응답 규격](https://docs.cloud.google.com/speech-to-text/docs/reference/rest/v2/BatchRecognizeFileResult)).

Cloud Run 배포는 자동으로 수행하지 않습니다. Cloud Run에서는 실행 서비스 계정의 ADC와 접근 가능한 MariaDB·ES·EXAONE 주소가 필요합니다. 현재 앱에는 로그인·노트별 권한이 없으므로 개인용 로컬 사용 또는 접근이 제한된 배포를 전제로 합니다.

## 환경 변수

| 변수 | 기본값 / 용도 |
| --- | --- |
| `DATABASE_URL` | `jdbc:mariadb://localhost:3306/voice_notes` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | 기존 MariaDB 인증 정보 |
| `DATABASE_INIT_MODE` | `always`; SQL 사전 실행 시 `never` |
| `EXAONE_BASE_URL`, `EXAONE_API_KEY` | 기존 API 기본 주소와 키 |
| `EXAONE_MODEL` | `LGAI-EXAONE/K-EXAONE-2.0-750B-A37B` |
| `EXAONE_TIMEOUT` | `10m` |
| `EXAONE_MAX_OUTPUT_TOKENS` | `4096` |
| `EXAONE_INCLUDE_CHAT_TEMPLATE_KWARGS` | `true` |
| `ELASTICSEARCH_URL`, `ELASTICSEARCH_API_KEY` | `http://localhost:9202`, 선택적 API 키 |
| `GOOGLE_CLOUD_PROJECT`, `VOICE_NOTES_BUCKET` | GCP 프로젝트와 버킷 |
| `VOICE_NOTES_SPEECH_LOCATION`, `VOICE_NOTES_SPEECH_MODEL` | `us`, `chirp_3` |
| `PORT` | `8080` |

## API와 검증

| 요청 | 동작 |
| --- | --- |
| `POST /api/notes` | multipart `file`, 선택적 `title`; GCS 업로드·MariaDB 저장 |
| `POST /api/notes/{id}/process` | 화자 분리 전사·ES 저장·EXAONE 요약 |
| `GET /api/notes` | 노트 목록; 전사 본문은 상세 조회에서 반환 |
| `GET /api/notes/{id}` | 요약·화자별 전사 상세 조회 |
| `GET /api/notes/search?q=검색어` | 전사 본문 검색 |
| `GET /api/notes/{id}/audio` | 원본 음성 재생 |

```powershell
.\gradlew.bat test bootJar
```

기본 테스트는 화자 응답 파싱과 로컬 HTTP 서버를 통한 EXAONE 프로토콜·오류 처리를 확인합니다. 실제 저장소를 포함한 검증은 별도 임시 MariaDB를 사용합니다.

```powershell
docker compose up -d elasticsearch
docker compose -f compose.test.yaml up -d --wait
$env:RUN_STORAGE_TESTS = 'true'
.\gradlew.bat test --rerun-tasks
Remove-Item Env:RUN_STORAGE_TESTS
docker compose -f compose.test.yaml down
```

저장소 테스트는 고정된 로컬 테스트 DB(`53317/voice_notes_test`)와 ES(`9202`)만 사용합니다. GCP·요약 호출은 모의 객체로 대체하며 테스트가 생성한 노트·전사만 정리합니다. 실제 GCP·EXAONE 호출은 사용자 인증 정보 입력 후 별도 확인이 필요합니다.

현재 처리 API는 동기식이며 한 앱 인스턴스에서 순차 처리합니다. 전사 대기 한도는 약 15분이며, 장시간 녹음·동시 사용자·서버 재시작 복구가 필요한 단계에서는 작업 큐와 작업 ID 영속화가 필요합니다.
