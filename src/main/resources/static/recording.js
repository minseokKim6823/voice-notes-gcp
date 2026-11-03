const MAX_AUDIO_BYTES = 24_000_000;
let recordingState = 'idle';
let captureVersion = 0;
let activeRecorder = null;
let recordingStream = null;
let recordingTimer = null;
let recordingPreview = null;

function clearRecordingPreview() {
  $('recording-time').hidden = true;
  $('recording-preview').pause();
  $('recording-preview').removeAttribute('src');
  $('recording-preview').hidden = true;
  if (recordingPreview) URL.revokeObjectURL(recordingPreview);
  recordingPreview = null;
}

function updateRecordingControls() {
  const active = recordingState !== 'idle';
  $('audio-file').disabled = active || uploadBusy;
  $('file-picker').classList.toggle('disabled', active || uploadBusy);
  $('record-button').disabled = uploadBusy || ['requesting', 'stopping'].includes(recordingState);
  $('record-button').textContent = recordingState === 'recording' ? '■ 녹음 종료하고 전사' :
    recordingState === 'requesting' ? '마이크 연결 중…' : recordingState === 'stopping' ? '녹음 정리 중…' : '● 녹음 시작';
  $('record-button').classList.toggle('recording', recordingState === 'recording');
  $('record-button').setAttribute('aria-pressed', String(recordingState === 'recording'));
  $('cancel-recording').hidden = !active;
  $('submit-button').disabled = active || uploadBusy || !selectedFile;
}

function releaseMicrophone() {
  clearInterval(recordingTimer);
  recordingTimer = null;
  recordingStream?.getTracks().forEach((track) => { track.onended = null; track.stop(); });
  recordingStream = null;
}

function cancelRecording() {
  captureVersion++;
  if (activeRecorder?.state === 'recording') activeRecorder.stop();
  activeRecorder = null;
  releaseMicrophone();
  recordingState = 'idle';
  $('recording-time').hidden = true;
  updateRecordingControls();
  message('녹음을 취소했습니다.');
}

function finishRecording() {
  if (recordingState !== 'recording') return;
  recordingState = 'stopping';
  updateRecordingControls();
  clearInterval(recordingTimer);
  activeRecorder.stop();
  releaseMicrophone();
}

async function startRecording() {
  if (uploadBusy) return;
  if (recordingState === 'recording') { finishRecording(); return; }
  if (recordingState !== 'idle') return;
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    message('녹음은 HTTPS 또는 localhost에서 사용할 수 있습니다.', true); return;
  }
  if (!window.MediaRecorder) {
    message('이 브라우저는 녹음을 지원하지 않습니다. 최신 Chrome, Edge 또는 Safari를 사용해 주세요.', true); return;
  }
  // Safari records MP4/AAC; Chromium and Firefox normally use WebM/Opus.
  const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
    .find((type) => MediaRecorder.isTypeSupported(type));
  if (!mimeType) { message('이 브라우저에 지원되는 녹음 형식이 없습니다.', true); return; }
  const version = ++captureVersion;
  recordingState = 'requesting';
  updateRecordingControls();
  message('브라우저에서 마이크 사용을 허용해 주세요.');
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({audio:true});
    if (version !== captureVersion) { stream.getTracks().forEach((track) => track.stop()); return; }
    recordingStream = stream;
    const recorder = new MediaRecorder(stream, {mimeType, audioBitsPerSecond:64000});
    activeRecorder = recorder;
    const chunks = [];
    let bytes = 0;
    recorder.ondataavailable = (event) => {
      if (version !== captureVersion || !event.data.size) return;
      chunks.push(event.data);
      bytes += event.data.size;
      if (bytes >= MAX_AUDIO_BYTES - 1_000_000 && recordingState === 'recording') {
        message('녹음 용량 한도에 가까워져 녹음을 종료하고 전사를 시작합니다.');
        finishRecording();
      }
    };
    recorder.onerror = () => {
      if (version !== captureVersion) return;
      cancelRecording();
      message('녹음 중 오류가 발생했습니다. 마이크를 확인하고 다시 녹음해 주세요.', true);
    };
    recorder.onstop = () => {
      if (version !== captureVersion) return;
      releaseMicrophone();
      activeRecorder = null;
      recordingState = 'idle';
      $('recording-time').textContent = $('recording-time').textContent.replace('녹음 중', '녹음 완료');
      const mime = recorder.mimeType.split(';')[0];
      const extension = {'audio/webm':'webm', 'audio/mp4':'m4a', 'audio/ogg':'ogg'}[mime];
      const file = new File(chunks, `recording-${Date.now()}.${extension}`, {type:mime});
      if (!file.size || file.size > MAX_AUDIO_BYTES) {
        updateRecordingControls();
        message('녹음이 비어 있거나 24MB를 초과했습니다. 짧게 다시 녹음해 주세요.', true); return;
      }
      selectedFile = file;
      recordingPreview = URL.createObjectURL(file);
      $('recording-preview').src = recordingPreview;
      $('recording-preview').hidden = false;
      $('selected-file').textContent = `녹음 완료 · ${(file.size / 1_000_000).toFixed(1)}MB`;
      updateRecordingControls();
      // No file picker or second submit click is needed after recording.
      void submitNote();
    };
    recorder.start(1000);
    selectedFile = null;
    $('audio-file').value = '';
    clearRecordingPreview();
    showComposer();
    recordingState = 'recording';
    const started = performance.now();
    const updateTime = () => {
      const seconds = Math.floor((performance.now() - started) / 1000);
      $('recording-time').textContent = `녹음 중 ${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
    };
    updateTime();
    $('recording-time').hidden = false;
    recordingTimer = setInterval(updateTime, 1000);
    stream.getAudioTracks().forEach((track) => { track.onended = finishRecording; });
    $('selected-file').textContent = '녹음을 종료하면 전사와 요약이 자동으로 시작됩니다.';
    updateRecordingControls();
    message('녹음 중입니다. 마치면 녹음 종료하고 전사를 눌러 주세요.');
  } catch (error) {
    stream?.getTracks().forEach((track) => track.stop());
    if (version !== captureVersion) return;
    releaseMicrophone();
    activeRecorder = null;
    recordingState = 'idle';
    updateRecordingControls();
    const detail = error.name === 'NotAllowedError' ? '마이크 권한을 허용한 뒤 다시 시도해 주세요.' :
      error.name === 'NotFoundError' ? '연결된 마이크를 찾을 수 없습니다.' : '마이크가 연결되어 있고 다른 앱에서 사용 중이지 않은지 확인해 주세요.';
    message(detail, true);
  }
}

function initRecording() {
  $('record-button').onclick = startRecording;
  $('cancel-recording').onclick = cancelRecording;
  window.addEventListener('beforeunload', (event) => {
    if (recordingState !== 'idle' || uploadBusy) { event.preventDefault(); event.returnValue = ''; }
  });
  window.addEventListener('pagehide', () => { cancelRecording(); clearRecordingPreview(); });
  updateRecordingControls();
}
