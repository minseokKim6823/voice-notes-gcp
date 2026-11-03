const MAX_AUDIO_BYTES = 24_000_000;
let recordingState = 'idle';
let captureVersion = 0;
let activeRecorder = null;
let recordingStream = null;
let recordingTimer = null;
let recordingPreview = null;
let recordingBookmarks = [];
let recordingElapsedMs = 0;
let recordingResumedAt = 0;
let meterContext = null;
let meterFrame = null;

function recordingPositionMs() {
  return recordingElapsedMs + (recordingState === 'recording' ? performance.now() - recordingResumedAt : 0);
}

function updateRecordingTime() {
  const seconds = Math.floor(recordingPositionMs() / 1000);
  $('recording-time').textContent = `${recordingState === 'paused' ? '일시정지' : recordingState === 'idle' ? '녹음 완료' : '녹음 중'} ${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

function drawMeter(values) {
  const canvas = $('mic-waveform');
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.lineWidth = 2;
  ctx.strokeStyle = recordingState === 'recording' ? '#3e8257' : '#b6c8b6';
  ctx.beginPath();
  for (let index = 0; index < values.length; index++) {
    const x = index * canvas.width / (values.length - 1);
    const y = canvas.height / 2 + (values[index] - 128) / 128 * canvas.height * .45;
    if (!index) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  }
  ctx.stroke();
}

function stopMeter() {
  if (meterFrame !== null) cancelAnimationFrame(meterFrame);
  meterFrame = null;
  if (meterContext) void meterContext.close().catch(() => {});
  meterContext = null;
  drawMeter(new Uint8Array(128).fill(128));
}

function startMeter(stream) {
  stopMeter();
  const Audio = window.AudioContext || window.webkitAudioContext;
  if (!Audio) return;
  try {
    meterContext = new Audio();
    const source = meterContext.createMediaStreamSource(stream);
    const analyser = meterContext.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);
    const values = new Uint8Array(analyser.fftSize);
    const draw = () => {
      if (recordingState === 'recording') analyser.getByteTimeDomainData(values); else values.fill(128);
      drawMeter(values);
      meterFrame = requestAnimationFrame(draw);
    };
    void meterContext.resume().catch(() => {});
    draw();
  } catch (_) { stopMeter(); }
}

function renderRecordingBookmarks() {
  $('recording-bookmark-list').replaceChildren();
  for (const bookmark of recordingBookmarks) {
    const chip = element('span', 'bookmark-chip');
    const text = element('span', '', `☆ ${timeLabel(bookmark.positionMs)} ${bookmark.label}`);
    text.style.padding = '8px';
    chip.append(text);
    $('recording-bookmark-list').append(chip);
  }
  $('recording-bookmarks').hidden = !['recording','paused'].includes(recordingState) && !recordingBookmarks.length;
}

function pauseRecording() {
  if (recordingState === 'recording') {
    recordingElapsedMs = recordingPositionMs();
    activeRecorder.pause();
    recordingState = 'paused';
  } else if (recordingState === 'paused') {
    activeRecorder.resume();
    recordingResumedAt = performance.now();
    recordingState = 'recording';
  }
  updateRecordingTime();
  updateRecordingControls();
}

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
  const capturing = ['recording','paused'].includes(recordingState);
  $('record-button').textContent = capturing ? '■ 녹음 종료하고 전사' :
    recordingState === 'requesting' ? '마이크 연결 중…' : recordingState === 'stopping' ? '녹음 정리 중…' : '● 녹음 시작';
  $('record-button').classList.toggle('recording', recordingState === 'recording');
  $('record-button').setAttribute('aria-pressed', String(capturing));
  $('pause-recording').hidden = !capturing;
  $('pause-recording').textContent = recordingState === 'paused' ? '녹음 계속' : '일시정지';
  $('with-summary').disabled = active || uploadBusy;
  $('add-recording-bookmark').disabled = !capturing;
  $('recording-bookmark-label').disabled = !capturing;
  $('mic-state').textContent = recordingState === 'recording' ? '마이크 입력 중 · 종료 후 전사' : recordingState === 'paused' ? '일시정지 · 이 시간은 녹음에 포함되지 않습니다' : '녹음을 시작하면 마이크 입력이 표시됩니다';
  $('cancel-recording').hidden = !active;
  $('submit-button').disabled = active || uploadBusy || !selectedFile;
}

function releaseMicrophone() {
  clearInterval(recordingTimer);
  recordingTimer = null;
  recordingStream?.getTracks().forEach((track) => { track.onended = null; track.stop(); });
  recordingStream = null;
  stopMeter();
}

function cancelRecording() {
  captureVersion++;
  if (['recording','paused'].includes(activeRecorder?.state)) activeRecorder.stop();
  if (recordingState !== 'requesting') recordingBookmarks = [];
  activeRecorder = null;
  releaseMicrophone();
  recordingState = 'idle';
  $('recording-time').hidden = true;
  updateRecordingControls();
  renderRecordingBookmarks();
  message('녹음을 취소했습니다.');
}

function finishRecording() {
  if (!['recording','paused'].includes(recordingState)) return;
  recordingElapsedMs = recordingPositionMs();
  recordingState = 'stopping';
  updateRecordingControls();
  clearInterval(recordingTimer);
  activeRecorder.stop();
  releaseMicrophone();
}

async function startRecording() {
  if (uploadBusy) return;
  if (['recording','paused'].includes(recordingState)) { finishRecording(); return; }
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
      if (bytes >= MAX_AUDIO_BYTES - 1_000_000 && ['recording','paused'].includes(recordingState)) {
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
      recordingElapsedMs = recordingPositionMs();
      releaseMicrophone();
      activeRecorder = null;
      recordingState = 'idle';
      updateRecordingTime();
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
      renderRecordingBookmarks();
      // No file picker or second submit click is needed after recording.
      void submitNote();
    };
    recorder.start(1000);
    selectedFile = null;
    $('audio-file').value = '';
    clearRecordingPreview();
    showComposer();
    recordingState = 'recording';
    recordingElapsedMs = 0;
    recordingResumedAt = performance.now();
    recordingBookmarks = [];
    renderRecordingBookmarks();
    updateRecordingTime();
    $('recording-time').hidden = false;
    recordingTimer = setInterval(updateRecordingTime, 200);
    startMeter(stream);
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
  $('pause-recording').onclick = pauseRecording;
  $('add-recording-bookmark').onclick = () => {
    if (!['recording','paused'].includes(recordingState) || recordingBookmarks.length >= 200) return;
    recordingBookmarks.push({id:crypto.randomUUID(), positionMs:Math.round(recordingPositionMs()), label:$('recording-bookmark-label').value.trim() || '중요한 순간'});
    $('recording-bookmark-label').value = '';
    renderRecordingBookmarks();
  };
  window.addEventListener('beforeunload', (event) => {
    if (recordingState !== 'idle' || uploadBusy) { event.preventDefault(); event.returnValue = ''; }
  });
  window.addEventListener('pagehide', () => { cancelRecording(); clearRecordingPreview(); });
  updateRecordingControls();
  stopMeter();
}
