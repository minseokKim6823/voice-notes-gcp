let progressWatch = null;
const progressLabels = {
  UPLOADED:'업로드 완료', PREPARING:'처리 준비 중', TRANSCRIBING:'음성을 전사하고 있습니다',
  SAVING_TRANSCRIPT:'전사를 저장하고 있습니다', SUMMARIZING:'AI 요약을 만들고 있습니다',
  SAVING_NOTE:'요약을 저장하고 있습니다', COMPLETE:'전사와 요약이 완료되었습니다', FAILED:'처리가 중단되었습니다'
};

function renderProgress(note) {
  const panel = $('processing-progress-panel');
  panel.hidden = note.status === 'UPLOADED';
  panel.classList.toggle('failed', note.status === 'FAILED');
  const percent = note.status === 'READY' ? 100 : Math.max(0, Math.min(99, note.progressPercent || 0));
  $('processing-progress').value = percent;
  $('processing-progress-percent').textContent = `${percent}%`;
  const stage = note.status === 'READY' ? 'COMPLETE' : note.status === 'FAILED' ? 'FAILED' : note.processingStage;
  $('processing-progress-label').textContent = progressLabels[stage] || progressLabels.PREPARING;
}

function stopProgressWatch() {
  if (progressWatch) {
    clearTimeout(progressWatch.timer);
    progressWatch.controller?.abort();
    progressWatch = null;
  }
  $('progress-connection').hidden = true;
}

function watchProgress(note) {
  renderProgress(note);
  if (note.status !== 'PROCESSING') { stopProgressWatch(); return; }
  if (progressWatch?.id === note.id) return;
  stopProgressWatch();
  const watch = {id:note.id, seenProcessing:false};
  progressWatch = watch;
  const poll = async () => {
    if (progressWatch !== watch || currentId !== watch.id) return;
    watch.controller = new AbortController();
    const timeout = setTimeout(() => watch.controller.abort(), 5000);
    try {
      const progress = await api(`/api/notes/${watch.id}/progress`, {signal:watch.controller.signal, cache:'no-store'});
      if (progressWatch !== watch || currentId !== watch.id) return;
      $('progress-connection').hidden = true;
      if (progress.status === 'PROCESSING') {
        watch.seenProcessing = true;
        currentNote = {...currentNote, ...progress};
        renderProgress(currentNote);
      } else if (['READY','FAILED'].includes(progress.status) && (watch.seenProcessing || !processingIds.has(watch.id))) {
        // A poll can beat the POST that starts a rerun; ignore the previous result until the job starts.
        const completed = await api(`/api/notes/${watch.id}`, {signal:watch.controller.signal, cache:'no-store'});
        if (progressWatch === watch && currentId === watch.id) { display(completed); await loadNotes(); }
      }
    } catch (_) {
      if (progressWatch === watch && currentId === watch.id) $('progress-connection').hidden = false;
    } finally {
      clearTimeout(timeout);
      if (progressWatch === watch && currentId === watch.id) watch.timer = setTimeout(poll, 1000);
    }
  };
  watch.timer = setTimeout(poll, 100);
}

function uploadNote(body) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    const panel = $('upload-progress-panel');
    panel.hidden = false;
    $('upload-progress').value = 0;
    $('upload-progress-label').textContent = '음성 업로드 중';
    $('upload-progress-percent').textContent = '0%';
    request.open('POST', '/api/notes');
    request.upload.onprogress = (event) => {
      if (!event.lengthComputable) { $('upload-progress').removeAttribute('value'); return; }
      const percent = Math.min(100, Math.floor(event.loaded * 100 / event.total));
      $('upload-progress').value = percent;
      $('upload-progress-percent').textContent = `${percent}%`;
      if (percent === 100) {
        $('upload-progress-label').textContent = '음성 파일을 저장하고 있습니다';
        $('upload-progress').removeAttribute('value');
        $('upload-progress-percent').textContent = '';
      }
    };
    request.onload = () => {
      let result;
      try { result = JSON.parse(request.responseText); } catch (_) { result = {error:`요청 실패 (${request.status})`}; }
      if (request.status >= 200 && request.status < 300) resolve(result);
      else reject(new Error(result.error || `요청 실패 (${request.status})`));
    };
    request.onerror = () => reject(new Error('음성을 업로드할 수 없습니다. 연결 상태를 확인하고 다시 시도하세요.'));
    request.onabort = () => reject(new Error('음성 업로드가 중단되었습니다.'));
    request.onloadend = () => { panel.hidden = true; };
    request.send(body);
  });
}
