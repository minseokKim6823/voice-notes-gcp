# 전사만 처리와 오디오 구간 재생

`POST /api/notes/{id}/transcribe`는 `VOICE_NOTES_STT_PROVIDER`로 선택한 전사 엔진을 사용한다. Whisper 모드에서 이 API만 Google을 직접 호출하던 경로를 수정했다. LLM을 호출하지 않고, 전사를 저장한 뒤 `TRANSCRIBED`, `TRANSCRIPTION_COMPLETE`, `progressPercent: 100`을 반환한다. 이미 전사가 저장되어 있으면 재사용한다.

`POST /api/notes/{id}/summary`는 그 전사로 나중에 요약을 생성할 수 있다. 기본 `/process` 경로는 기존처럼 전사와 요약을 순서대로 처리한다.

`GET /api/notes/{id}/audio`는 Spring의 `Resource` 응답을 사용한다. 브라우저가 `Range: bytes=10-19`처럼 일부 바이트를 요청하면 206 Partial Content와 `Content-Range`를 반환해 구간 이동을 지원한다. 원본을 가져오는 기존 GCS 경로는 유지하므로 요청 시 서버가 원본 전체를 메모리로 불러온다는 한계는 있다.

`WhisperWorkflowTest`는 전사만 처리할 때 선택된 Whisper 엔진을 사용하고 LLM/Google 전사를 호출하지 않는지 확인한다. `AudioPlaybackTest`는 HTTP Range 상태·헤더와 정확한 바이트 구간을 검증한다.
