# 보이스 노트

회의, 강의, 인터뷰의 음성을 텍스트와 요약으로 정리하고, 나중에 전사 내용으로 다시 찾을 수 있는 개인 학습용 음성 노트입니다. 브라우저에서 직접 녹음하거나 기존 음성 파일을 올리면 한국어 전사, 화자 구분, AI 요약을 하나의 노트에서 확인할 수 있습니다.

Spring Boot 3.5 / Java 17로 서버를 구성하고 Google Cloud, EXAONE, MariaDB, MongoDB를 연결합니다.

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 브라우저 녹음 | 마이크로 음성을 녹음하고 WebM 파일로 업로드합니다. 마이크 권한과 WebM 녹음을 지원하는 브라우저가 필요하며, localhost 또는 HTTPS에서 사용할 수 있습니다. |
| 음성 파일 업로드 | WebM, WAV, MP3, FLAC, OGG 형식의 30MB 이하 파일을 받습니다. 제목은 최대 120자이며, 비워 두면 파일 이름을 사용합니다. |
| 실시간 진행률 | 업로드 전송률과 전사·저장·요약 진행률을 표시합니다. 서버 진행 상태를 1초 간격으로 조회하며 처리 중 노트를 다시 열면 이어서 표시합니다. |
| 한국어 전사와 화자 구분 | Google Speech-to-Text V2의 Chirp 3 모델로 음성을 한국어 텍스트로 변환하고 화자별 발언을 구분합니다. 화자 라벨은 발언자를 구별하는 표시이며 실제 이름을 식별하지는 않습니다. |
| 화자 관리 | 화자 이름을 최대 60자로 지정하고, 잘못 나뉜 화자의 전체 발언을 다른 화자로 합칩니다. 이름과 화자 ID를 별도로 저장합니다. |
| 발언별 화자 수정·분리 | 개별 발언을 기존 화자로 옮기거나 새 화자를 지정해 분리합니다. 전사 문장도 최대 5,000자로 수정할 수 있습니다. 발언 순서와 시간 정보는 유지합니다. |
| 화자별 보기·내보내기 | 화자별 발언 수와 구간 시간을 표시하고, 선택한 화자의 발언만 필터링합니다. 전체 또는 필터링한 전사를 화자 이름과 함께 UTF-8 텍스트 파일로 저장합니다. |
| 발언 구간 재생 | 시간 정보가 있는 발언의 시간 버튼을 누르면 원본 음성의 해당 위치부터 재생합니다. 시간 정보가 없는 기존 전사에는 재생 버튼을 비활성화합니다. |
| AI 요약 | EXAONE에 전사 내용을 보내 화자별 발언, 핵심 내용, 결정 사항, 할 일을 정리하도록 요청합니다. 긴 녹취는 12,000자씩 나누어 요약한 뒤 하나의 요약으로 통합합니다. |
| 수정 후 요약 재생성 | 화자나 발언을 수정하면 기존 요약에 갱신 안내를 표시합니다. **요약 다시 생성**은 수정된 전사를 사용하며 Google 전사를 다시 호출하지 않습니다. 요약 실패 시 기존 요약과 수정 내용은 유지합니다. |
| 노트 목록과 상세 조회 | 저장된 노트를 생성일 기준 최신순으로 보여 줍니다. 노트 상세 화면에서 제목, 생성 시간, 처리 상태, AI 요약, 전체 전사를 확인할 수 있습니다. |
| 원본 음성 재생 | 노트에 저장된 원본 음성을 브라우저 오디오 플레이어로 재생해 전사 내용과 비교할 수 있습니다. |
| 전사 내용 검색 | MongoDB에 저장된 전사 본문에서 검색어가 포함된 노트를 찾습니다. 한글 부분 문자열을 지원하고 영문 대소문자는 구분하지 않습니다. |
| 오류 확인과 재처리 | 처리가 실패하면 오류를 표시하고 **다시 처리하기** 버튼으로 재시도할 수 있습니다. 전사 저장이 끝난 뒤 요약에 실패했다면 저장된 전사를 재사용합니다. |

## 사용 방법

1. 아래 실행 안내에 따라 서버와 필요한 서비스를 준비한 뒤 `http://localhost:8080`에 접속합니다.
2. **새 노트** 화면에서 제목을 입력하고 **파일 선택**으로 음성을 고르거나 **녹음 시작**으로 녹음합니다. 녹음을 마치면 **녹음 종료**를 누릅니다.
3. **전사 및 요약 시작**을 누르면 음성을 업로드하고 전사와 요약을 순서대로 실행합니다. 완료까지 걸리는 시간은 음성 길이와 외부 API 처리 시간에 따라 달라집니다.
4. 노트 상세 화면에서 원본 음성, AI 요약, 전체 전사를 확인합니다. 실패한 노트는 오류 내용을 확인한 뒤 **다시 처리하기**를 누릅니다.
5. 왼쪽 노트 목록에서 이전 기록을 열거나 **전사 내용 검색**에 검색어를 입력해 관련 노트를 찾습니다.
6. **화자 관리**에서 이름을 저장하거나 합칠 화자를 선택합니다. 각 발언의 **화자 / 발언 편집**에서는 화자 변경, 새 화자로 분리, 문장 수정을 할 수 있습니다.
7. 수정 후 **요약 다시 생성**을 눌러 요약을 갱신합니다. **화자별 보기**로 필터링한 뒤 **텍스트 저장**을 누르면 선택한 화자의 발언만 저장합니다.

### 화자 구분 결과 수정

자동 전사 후 실제 발언자에 맞게 화자를 정리할 수 있습니다. 화자 ID는 노트 안에서 발언자를 구별하며, 같은 이름을 입력해도 서로 다른 ID의 화자가 자동으로 합쳐지지는 않습니다.

| 상황 | 화면에서 수행할 작업 | 반영 범위 |
| --- | --- | --- |
| 화자 번호 대신 이름을 표시하고 싶을 때 | **화자 관리**에서 이름을 입력하고 저장 | 해당 화자의 모든 발언에 적용하며 화자 ID는 유지합니다. |
| 같은 사람이 여러 화자로 나뉘었을 때 | 원본 화자 카드에서 대상 화자를 선택하고 **이 화자로 합치기** | 원본 화자의 모든 발언을 대상 ID와 이름으로 옮깁니다. |
| 한 발언만 다른 사람에게 배정됐을 때 | 해당 발언의 **화자 / 발언 편집**에서 기존 화자를 선택하고 **화자 저장** | 선택한 발언만 옮깁니다. |
| 기존 목록에 없는 사람이 발언했을 때 | 해당 발언에서 **＋ 새 화자로 분리**를 선택하고 이름을 입력한 뒤 **화자 저장** | 새 ID를 만들어 선택한 발언에만 배정합니다. 다른 발언은 이후 기존 화자 선택으로 옮길 수 있습니다. |
| 전사 문장을 바로잡고 싶을 때 | 해당 발언의 문장을 수정하고 **발언 내용 저장** | 화자, 발언 순서, 시간 정보는 유지하고 본문 검색에도 반영합니다. |

이름은 앞뒤 공백을 제거한 1~60자로 입력하며 줄바꿈과 제어 문자는 허용하지 않습니다. 발언 내용은 앞뒤 공백을 제거한 1~5,000자로 입력합니다. 전사나 요약을 처리하는 동안에는 편집과 요약 재생성을 사용할 수 없습니다.

수정 내용은 저장 즉시 전사에 반영됩니다. 기존 요약에는 갱신 안내가 표시되며, **요약 다시 생성**을 누르면 수정된 이름과 전체 발언으로 새 요약을 만듭니다. 재생성에 실패하면 기존 요약과 수정한 전사를 유지하므로 **다시 처리하기**로 재시도할 수 있습니다.

**화자별 보기**는 전사 표시와 **텍스트 저장**에 적용됩니다. 요약 재생성은 선택한 필터와 관계없이 노트 전체 전사를 사용합니다. 저장 파일에는 노트 제목, 생성 시각, 화자 이름, 발언 내용과 제공된 구간 시간이 포함되며, 한글을 읽을 수 있도록 UTF-8 BOM을 붙입니다.

정밀한 발언 시간과 구간 재생이 필요하면 `VOICE_NOTES_SPEECH_WORD_TIME_OFFSETS=true` 또는 YAML의 `voice-notes.speech-word-time-offsets: true`를 설정한 뒤 새 음성을 전사합니다. [Chirp 3 공식 문서](https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3)에 따르면 단어 시간 정보를 켠 배치 전사는 최대 20분 음성을 지원하며 전사 정확도에 영향이 있을 수 있어 기본값은 `false`입니다. 이미 저장된 전사의 시간 정보는 소급 생성하지 않습니다. 시간 표시는 제공된 발언 구간 길이의 합계입니다.

### 진행 상태 확인

업로드 화면은 브라우저가 전송한 바이트 비율을 표시하고, 전송 후 원본 음성 저장을 기다리는 동안 대기 상태를 표시합니다. 노트 상세 화면은 서버의 처리 단계와 전체 진행률을 표시합니다.

| 단계 | 전체 진행률 | 갱신 기준 |
| --- | --- | --- |
| 처리 준비 | 0% | 처리 요청 시작 |
| 음성 전사 | 5~75% | Google [작업 메타데이터](https://docs.cloud.google.com/speech-to-text/docs/reference/rest/v2/OperationMetadata)의 `progressPercent` |
| 전사 저장 | 78% | MongoDB 저장 시작 |
| AI 요약 | 80~95% | 완료한 분할 요약·통합 요청 수 |
| 요약 저장 | 98% | MariaDB 최종 저장 시작 |
| 완료 | 100% | 전사·요약 저장 완료 |

전체 진행률은 단계별 가중치를 적용한 값이며, 외부 API가 새 정보를 보내지 않거나 한 요약 요청을 기다리는 동안에는 같은 값이 유지될 수 있습니다. 저장된 전사가 있으면 전사 단계를 건너뜁니다. 실패하면 마지막 진행률과 오류를 표시하며, 재시도 시 새 진행률로 시작합니다. 진행 상태는 MariaDB에 저장합니다. 브라우저를 새로고침한 뒤 처리 중인 노트를 열어도 이어서 조회하며, 연결 오류가 발생하면 자동으로 재조회합니다.

## 처리 흐름

```mermaid
flowchart LR
    A[녹음 또는 파일 선택] --> B[GCS에 원본 음성 저장]
    B --> C[Speech-to-Text 전사와 화자 구분]
    C --> D[MongoDB에 전사 저장]
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
| 노트 정보·처리 상태·진행률·요약 저장 | 기존 MariaDB의 `voice_notes` 테이블 |
| 화자별 전사 저장·본문 검색 | MongoDB, `voice_note_transcripts` 컬렉션 |

## 실행

Java 17, Docker, 접속 가능한 MariaDB를 준비합니다. MariaDB 서버·데이터베이스·계정은 기존 것을 사용하며 이 프로젝트에서 만들지 않습니다.

```powershell
if (-not (Test-Path application-local.yml)) { Copy-Item application-local.yml.example application-local.yml }
# application-local.yml의 DB, GCP, EXAONE 값을 입력합니다.
docker compose up -d mongodb
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

## MariaDB와 MongoDB

```yaml
spring:
  datasource:
    url: jdbc:mariadb://DB_HOST:3306/DB_NAME
    username: DB_USER
    password: DB_PASSWORD
```

시작할 때 `src/main/resources/db/schema-mariadb.sql`로 테이블을 준비하고 Hibernate가 스키마를 검증합니다. 기존 `voice_notes`에는 요약 갱신 표시와 진행 상태 컬럼을 추가하며 기존 노트는 유지합니다. [MariaDB의 `ADD COLUMN IF NOT EXISTS`](https://mariadb.com/docs/server/reference/sql-statements/data-definition/alter/alter-table)로 반복 실행할 수 있습니다. 앱 계정에는 읽기·쓰기, 최초 생성 및 컬럼 추가를 위한 `ALTER` 권한이 필요합니다. DDL 권한이 없거나 `spring.sql.init.mode=never`를 사용하는 환경은 관리자가 아래 SQL을 한 번 실행하세요. 기존 테이블의 다른 컬럼 구조가 다르면 앱 전용 데이터베이스를 지정하세요.

```sql
ALTER TABLE voice_notes ADD COLUMN IF NOT EXISTS summary_stale BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE voice_notes ADD COLUMN IF NOT EXISTS progress_percent INT NOT NULL DEFAULT 0;
ALTER TABLE voice_notes ADD COLUMN IF NOT EXISTS processing_stage VARCHAR(255) NOT NULL DEFAULT 'UPLOADED';
```

MongoDB에는 노트 ID를 `_id`로 사용하고 `segments` 배열에 전체 전사를 저장합니다. 화자 이름은 각 발언의 `speakerName`에 저장하며, 이 필드가 없는 문서는 화자 번호로 조회합니다. 화자 변경·병합·문장 수정은 노트의 전사 문서 한 개를 원자적으로 교체하며, 문장 수정은 본문 검색에도 반영됩니다.

기본 Compose는 **MongoDB만** 실행합니다. 포트는 `127.0.0.1:27017`, 데이터는 Docker 볼륨에 보관합니다. 인증을 끈 로컬 개발 구성입니다. 외부 MongoDB는 환경 변수 `MONGODB_URI` 또는 `spring.data.mongodb.uri`로 연결합니다. 검색은 정규식 문자를 이스케이프한 부분 문자열 검색이며, 검색어에 포함된 `.*`나 괄호도 문자 그대로 취급합니다. 형태소 분석·검색 관련도 순위는 제공하지 않으며 대규모 데이터에서는 검색 인덱스 구성을 별도로 검토해야 합니다.

기존 Elasticsearch 전사가 있으면 이전 서버를 실행한 상태에서 아래 도구로 옮깁니다(Node.js 22 이상). 기본 원본 주소는 `http://localhost:9202`이며 다른 주소·키는 `LEGACY_ELASTICSEARCH_URL`, `LEGACY_ELASTICSEARCH_API_KEY`로 지정합니다. 대상은 Compose의 MongoDB이며 외부 대상은 `MONGODB_URI`로 지정합니다. 원본 데이터는 수정하지 않고, 이미 MongoDB에 있는 노트 ID는 건너뛰므로 반복 실행할 수 있습니다. 실행 중 앱의 전사·편집 요청을 멈추고 이관 후 기존 노트를 확인하세요.

```powershell
docker compose up -d mongodb
node scripts/migrate-transcripts.cjs --dry-run
node scripts/migrate-transcripts.cjs
```

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

Cloud Run 배포는 자동으로 수행하지 않습니다. Cloud Run에서는 실행 서비스 계정의 ADC와 접근 가능한 MariaDB·MongoDB·EXAONE 주소가 필요합니다. 현재 앱에는 로그인·노트별 권한이 없으므로 개인용 로컬 사용 또는 접근이 제한된 배포를 전제로 합니다.

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
| `MONGODB_URI` | `mongodb://localhost:27017/voice_notes?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000`; MongoDB 연결 URI |
| `GOOGLE_CLOUD_PROJECT`, `VOICE_NOTES_BUCKET` | GCP 프로젝트와 버킷 |
| `VOICE_NOTES_SPEECH_LOCATION`, `VOICE_NOTES_SPEECH_MODEL` | `us`, `chirp_3` |
| `VOICE_NOTES_SPEECH_WORD_TIME_OFFSETS` | `false`; `true`이면 단어 시간 요청, Chirp 3 배치 음성 최대 20분 |
| `PORT` | `8080` |

## API와 검증

| 요청 | 동작 |
| --- | --- |
| `POST /api/notes` | multipart `file`, 선택적 `title`; GCS 업로드·MariaDB 저장 |
| `POST /api/notes/{id}/process` | 화자 분리 전사·MongoDB 저장·EXAONE 요약 |
| `GET /api/notes/{id}/progress` | `id`, `status`, `progressPercent`, `processingStage`, `error`; 전사 본문 조회 없이 진행 상태 반환 |
| `GET /api/notes` | 노트 목록; 전사 본문은 상세 조회에서 반환 |
| `GET /api/notes/{id}` | 요약·화자별 전사 상세 조회 |
| `PATCH /api/notes/{id}/speakers/{speaker}` | JSON `{"name":"민수"}`; 해당 화자의 모든 발언에 이름 적용 |
| `POST /api/notes/{id}/speakers/{speaker}/merge` | JSON `{"targetSpeaker":"2"}`; 원본 화자의 전체 발언을 대상 화자로 병합 |
| `PATCH /api/notes/{id}/segments/{sequence}/speaker` | JSON `{"speaker":"2"}`(기존 ID) 또는 `{"name":"새 참가자"}`(새 화자) 중 하나; 개별 발언 화자 변경 |
| `PATCH /api/notes/{id}/segments/{sequence}` | JSON `{"text":"목요일까지 검증합니다."}`; 발언 내용 수정 |
| `POST /api/notes/{id}/summary` | 수정한 화자·발언으로 요약 재생성; 전사는 재사용 |
| `GET /api/notes/search?q=검색어` | 전사 본문 검색 |
| `GET /api/notes/{id}/audio` | 원본 음성 재생 |

상세 조회와 편집 응답에는 `speakers`와 `segments` 배열, `summaryStale`이 포함됩니다. `speakers`의 각 항목은 `id`, 표시 이름인 `name`, 발언 수인 `segmentCount`, 구간 길이 합계인 `durationMs`를 제공합니다. `segments`에는 `sequence`, `speaker`(화자 ID), `speakerName`, `text`, `startMs`, `endMs`가 들어 있습니다. 이름을 지정하지 않은 화자는 `화자 {ID}`로 표시하며, 라벨이 없는 발언은 `미확인 화자`로 표시합니다.

편집 요청은 `Content-Type: application/json`을 사용합니다. URL과 `targetSpeaker`·`speaker`에는 상세 응답의 화자 ID를, `{sequence}`에는 해당 발언의 `sequence`를 사용하세요. 새 화자로 분리하면 서버가 `custom-<UUID>` ID를 생성합니다. 기존 화자 ID와 새 이름을 함께 입력하거나 모두 비워 두면 `400`을 반환합니다. 없는 화자·발언, 잘못된 이름·본문, 처리 중 편집도 `400`, 외부 서비스의 I/O 오류는 `502`와 JSON `{"error":"오류 내용"}`으로 반환합니다.

실제 변경이 저장되면 `summaryStale: true`가 되고, 요약 재생성에 성공하면 `false`로 돌아옵니다. 값이 동일한 편집은 전사를 다시 저장하거나 요약 갱신 상태를 바꾸지 않습니다.

```powershell
.\gradlew.bat test bootJar
```

기본 테스트는 화자 응답 파싱, 편집·병합·분리 API, 입력 검증, 요약 갱신과 실패 복구, MongoDB 저장·검색 규칙, 처리 중 진행률 조회, EXAONE 프로토콜을 확인합니다. 실제 저장소 검증은 별도 임시 MariaDB·MongoDB를 사용하며 기존 스키마에 컬럼을 추가하는 마이그레이션도 확인합니다.

Node.js 22 이상과 Chrome이 있으면 실제 브라우저에서 화자 편집, 필터, UTF-8 파일 내보내기, 구간 재생, 요약 재생성, 저장 실패 복구, 노트 이동, 실시간 진행률·연결 복구·새로고침·업로드 실패, 모바일 레이아웃을 검사할 수 있습니다. 외부 API와 DB를 사용하지 않으며 임시 프로필과 스크린샷은 `build/ui-test`에 생성합니다. Windows의 기본 Chrome 설치 경로를 사용하며 다른 경로는 `VOICE_NOTES_CHROME` 환경 변수로 지정합니다.

```powershell
node scripts/test-ui.cjs
node --test scripts/migrate-transcripts.test.cjs
```

```powershell
docker compose -f compose.test.yaml up -d --wait
$env:RUN_STORAGE_TESTS = 'true'
.\gradlew.bat test --rerun-tasks
Remove-Item Env:RUN_STORAGE_TESTS
docker compose -f compose.test.yaml down
```

저장소 테스트는 고정된 로컬 테스트 MariaDB(`53317/voice_notes_test`)와 MongoDB(`53318/voice_notes_test`)만 사용합니다. GCP·요약 호출은 모의 객체로 대체하며 테스트가 생성한 노트·전사만 정리합니다. 실제 GCP·EXAONE 호출은 사용자 인증 정보 입력 후 별도 확인이 필요합니다.

현재 처리 API는 동기식이며 한 앱 인스턴스에서 순차 처리합니다. 전사 대기 한도는 약 15분이며, 장시간 녹음·동시 사용자·서버 재시작 복구가 필요한 단계에서는 작업 큐와 작업 ID 영속화가 필요합니다.
