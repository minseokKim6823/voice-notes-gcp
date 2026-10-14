const $ = (id) => document.getElementById(id);
let selectedFile = null;
let recorder = null;
let chunks = [];
let currentId = null;
let currentNote = null;
const processingIds = new Set();
let selectionVersion = 0;

async function api(path, options = {}) {
  const response = await fetch(path, options);
  const raw = await response.text();
  let result;
  try { result = JSON.parse(raw); } catch (_) { result = {error: `요청 실패 (${response.status})`}; }
  if (!response.ok) throw new Error(result.error || `요청 실패 (${response.status})`);
  return result;
}

function message(text, error = false) {
  $('message').textContent = text;
  $('message').classList.toggle('error', error);
}

function showComposer() {
  selectionVersion++;
  currentId = null;
  currentNote = null;
  $('empty-view').hidden = false;
  $('note-view').hidden = true;
  document.querySelectorAll('.list-item').forEach((item) => item.classList.remove('active'));
}

async function openNote(id) {
  const version = ++selectionVersion;
  try {
    const note = await api(`/api/notes/${id}`);
    if (version === selectionVersion) display(note);
  } catch (error) { message(error.message, true); }
}

function display(note) {
  if (currentId !== note.id) speakerFilter = '';
  currentId = note.id;
  currentNote = note;
  $('empty-view').hidden = true;
  $('note-view').hidden = false;
  $('note-title').textContent = note.title;
  $('note-date').textContent = new Date(note.createdAt).toLocaleString('ko-KR');
  $('note-file').textContent = note.fileName;
  $('note-status').textContent = {UPLOADED:'업로드 완료',PROCESSING:'처리 중',READY:'완료',FAILED:'처리 실패'}[note.status] || note.status;
  $('note-status').className = `badge ${note.status.toLowerCase()}`;
  $('note-summary').textContent = note.summary || '요약이 아직 없습니다.';
  displaySpeakers(note);
  $('note-error').hidden = !note.error;
  $('note-error').textContent = note.error || '';
  $('retry-button').hidden = note.status === 'READY' || note.status === 'PROCESSING' || processingIds.has(note.id);
  $('retry-button').disabled = editingIds.has(note.id);
  const audioPath = `/api/notes/${note.id}/audio`;
  if ($('note-audio').getAttribute('src') !== audioPath) $('note-audio').src = audioPath;
  document.querySelectorAll('.list-item').forEach((item) => item.classList.toggle('active', item.dataset.id === note.id));
}

async function loadNotes() {
  try {
    const query = $('search').value.trim();
    const notes = await api(query ? `/api/notes/search?q=${encodeURIComponent(query)}` : '/api/notes');
    const list = $('note-list');
    list.replaceChildren();
    for (const note of notes) {
      const button = document.createElement('button');
      button.className = `list-item${note.id === currentId ? ' active' : ''}`;
      button.dataset.id = note.id;
      const title = document.createElement('strong');
      title.textContent = note.title;
      const date = document.createElement('small');
      date.textContent = `${new Date(note.createdAt).toLocaleDateString('ko-KR')} · ${{UPLOADED:'업로드 완료',PROCESSING:'처리 중',READY:'완료',FAILED:'실패'}[note.status] || note.status}`;
      button.append(title, date);
      button.onclick = () => openNote(note.id);
      list.append(button);
    }
  } catch (error) { message(error.message, true); }
}

async function processNote(id, action = 'process') {
  if (processingIds.has(id) || editingIds.has(id)) return;
  processingIds.add(id);
  if (currentId === id) {
    display({...currentNote, status:'PROCESSING'});
    $('retry-button').hidden = true;
    $('note-status').textContent = '처리 중';
    $('note-status').className = 'badge processing';
    $('note-error').hidden = true;
  }
  try {
    const note = await api(`/api/notes/${id}/${action}`, {method:'POST'});
    if (currentId === id) display(note);
  } catch (error) {
    message(error.message, true);
    if (currentId === id) {
      try { await openNote(id); } catch (_) {}
      if (currentId === id) {
        if (currentNote?.status === 'PROCESSING') currentNote = {...currentNote, status:'FAILED', error:error.message};
        $('note-error').hidden = false;
        $('note-error').textContent = error.message;
        $('retry-button').hidden = false;
      }
    }
  } finally {
    processingIds.delete(id);
    if (currentId === id && currentNote) display(currentNote);
    await loadNotes();
  }
}

$('audio-file').onchange = (event) => {
  selectedFile = event.target.files[0] || null;
  $('selected-file').textContent = selectedFile ? `${selectedFile.name} · ${(selectedFile.size / 1048576).toFixed(1)}MB` : '';
  $('submit-button').disabled = !selectedFile;
};

$('record-button').onclick = async () => {
  if (recorder?.state === 'recording') { recorder.stop(); return; }
  if (!window.MediaRecorder || !MediaRecorder.isTypeSupported('audio/webm')) {
    message('이 브라우저의 녹음 형식은 지원되지 않습니다. 음성 파일을 선택해 주세요.', true);
    return;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({audio:true});
    recorder = new MediaRecorder(stream, {mimeType:'audio/webm'});
    chunks = [];
    recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
    recorder.onstop = () => {
      const mime = recorder.mimeType.split(';')[0];
      selectedFile = new File(chunks, `recording-${Date.now()}.webm`, {type:mime});
      $('selected-file').textContent = `녹음 완료 · ${(selectedFile.size / 1048576).toFixed(1)}MB`;
      $('submit-button').disabled = false;
      $('record-button').textContent = '●  녹음 시작';
      $('record-button').classList.remove('recording');
      stream.getTracks().forEach((track) => track.stop());
    };
    recorder.start();
    $('record-button').textContent = '■  녹음 종료';
    $('record-button').classList.add('recording');
    message('녹음 중입니다. 마치면 녹음 종료를 누르세요.');
  } catch (error) { message(`마이크를 사용할 수 없습니다: ${error.message}`, true); }
};

$('submit-button').onclick = async () => {
  if (!selectedFile) return;
  if (selectedFile.size > 30 * 1024 * 1024) {
    message('음성 파일은 30MB 이하만 업로드할 수 있습니다.', true);
    return;
  }
  const body = new FormData();
  body.append('title', $('title').value);
  body.append('file', selectedFile);
  $('submit-button').disabled = true;
  message('업로드 중입니다…');
  try {
    const note = await api('/api/notes', {method:'POST', body});
    await loadNotes();
    display({...note, status:'PROCESSING'});
    message('음성을 전사하고 요약하는 중입니다. 길이에 따라 몇 분 걸릴 수 있습니다.');
    await processNote(note.id);
  } catch (error) { message(error.message, true); }
  finally { $('submit-button').disabled = false; }
};

$('retry-button').onclick = () => { if (currentId) processNote(currentId); };
$('summary-button').onclick = () => { if (currentId) processNote(currentId, 'summary'); };
$('speaker-filter').onchange = () => {
  speakerFilter = $('speaker-filter').value;
  if (currentNote) renderTranscript(currentNote);
};
$('export-button').onclick = exportTranscript;
$('back-button').onclick = showComposer;
$('new-note').onclick = showComposer;
let searchTimer;
$('search').oninput = () => { clearTimeout(searchTimer); searchTimer = setTimeout(loadNotes, 300); };
loadNotes();
