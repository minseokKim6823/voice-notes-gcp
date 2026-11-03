let transcriptQuery = '';
let searchPosition = -1;
let activeSequence = null;
let workspaceNoteId = null;
let savedBookmarks = [];
let bookmarkReadFailed = false;
let bookmarkLoadVersion = 0;
const pendingBookmarks = new Map();
let summarySettings = null;

function filteredSegments(note) {
  const query = transcriptQuery.toLocaleLowerCase();
  return (note.segments || []).filter((segment) => (!speakerFilter || segment.speaker === speakerFilter)
    && (!query || segment.text.toLocaleLowerCase().includes(query)));
}

function highlightText(text) {
  const fragment = document.createDocumentFragment();
  const query = transcriptQuery.toLocaleLowerCase();
  if (!query) { fragment.append(document.createTextNode(text)); return fragment; }
  const lower = text.toLocaleLowerCase();
  let start = 0;
  for (let position; (position = lower.indexOf(query, start)) !== -1;) {
    fragment.append(document.createTextNode(text.slice(start, position)), element('mark', '', text.slice(position, position + query.length)));
    start = position + query.length;
  }
  fragment.append(document.createTextNode(text.slice(start)));
  return fragment;
}

function updateTranscriptTools(note, segments) {
  $('transcript-result-count').textContent = transcriptQuery ? `검색 결과 ${segments.length}개 발언` : `${segments.length}개 발언`;
  $('previous-match').disabled = $('next-match').disabled = !transcriptQuery || !segments.length;
  $('export-srt').disabled = !segments.some((segment) => segment.endMs > segment.startMs);
  $('export-button').disabled = !segments.length;
  syncPlayback();
}

function moveSearch(direction) {
  if (!currentNote) return;
  const matches = filteredSegments(currentNote);
  if (!matches.length) return;
  searchPosition = (searchPosition + direction + matches.length) % matches.length;
  document.querySelectorAll('.search-current').forEach((row) => row.classList.remove('search-current'));
  const segment = matches[searchPosition];
  const row = document.querySelector(`[data-sequence="${segment.sequence}"]`);
  row?.classList.add('search-current');
  row?.scrollIntoView({block:'nearest', behavior:'smooth'});
  if (segment.endMs > segment.startMs) void playSegment(currentId, segment.startMs);
}

function syncPlayback() {
  if (!currentNote) return;
  const position = $('note-audio').currentTime * 1000;
  const segment = (currentNote.segments || []).find((item) => item.endMs > item.startMs && position >= item.startMs && position < item.endMs);
  const sequence = segment?.sequence ?? null;
  document.querySelectorAll('.transcript-segment').forEach((row) => {
    const active = sequence !== null && Number(row.dataset.sequence) === sequence;
    row.classList.toggle('is-playing', active);
    if (active) row.setAttribute('aria-current', 'true'); else row.removeAttribute('aria-current');
  });
  if (sequence !== activeSequence && sequence !== null && !$('note-audio').paused && $('follow-playback').checked
      && !document.querySelector('#note-transcript details[open]')) {
    document.querySelector(`[data-sequence="${sequence}"]`)?.scrollIntoView({block:'nearest', behavior:'smooth'});
  }
  activeSequence = sequence;
}

function renderSummary(text) {
  const panel = $('note-summary');
  panel.replaceChildren();
  panel.classList.add('summary-content');
  if (!text) panel.append(element('p', '', '전사를 마치면 핵심 요약, 결정 사항과 할 일을 확인할 수 있습니다.'));
  for (const line of (text || '').split('\n')) {
    if (!line.trim()) continue;
    if (/^#{1,3}\s/.test(line)) panel.append(element('h3', 'summary-section-title', line.replace(/^#{1,3}\s+/, '')));
    else panel.append(element('p', 'summary-line', line.replace(/^[-*]\s+/, '• ')));
  }
  $('copy-summary').disabled = $('export-summary').disabled = !text;
}

function downloadText(name, content, type = 'text/plain;charset=utf-8') {
  const url = URL.createObjectURL(new Blob(['\uFEFF', content], {type}));
  const link = element('a');
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function exportSrt() {
  if (!currentNote) return;
  const segments = filteredSegments(currentNote).filter((segment) => segment.endMs > segment.startMs);
  const stamp = (ms) => {
    const value = Math.floor(Math.max(0, ms));
    return `${String(Math.floor(value / 3600000)).padStart(2, '0')}:${String(Math.floor(value / 60000) % 60).padStart(2, '0')}:${String(Math.floor(value / 1000) % 60).padStart(2, '0')},${String(value % 1000).padStart(3, '0')}`;
  };
  if (!segments.length) { message('시간 정보가 있는 전사가 필요합니다.', true); return; }
  const subtitles = segments.map((segment, index) => `${index + 1}\n${stamp(segment.startMs)} --> ${stamp(segment.endMs)}\n${segment.text.replace(/[\r\n]+/g, ' ')}`).join('\n\n');
  downloadText(`${safeNoteName()}.srt`, subtitles + '\n');
  message('현재 표시된 전사의 SRT 자막을 저장했습니다.');
}

function safeNoteName() { return (currentNote?.title || '음성 기록').replace(/[\\/:*?"<>|]/g, '_').slice(0, 100); }

function pendingFor(id) {
  if (!pendingBookmarks.has(id)) {
    try {
      const value = JSON.parse(sessionStorage.getItem(`voice-notes.pending-bookmarks:${id}`) || '[]');
      pendingBookmarks.set(id, Array.isArray(value) ? value : []);
    } catch (_) { pendingBookmarks.set(id, []); }
  }
  return pendingBookmarks.get(id);
}

function retainPending(id, value) {
  pendingBookmarks.set(id, value);
  try {
    if (value.length) sessionStorage.setItem(`voice-notes.pending-bookmarks:${id}`, JSON.stringify(value));
    else sessionStorage.removeItem(`voice-notes.pending-bookmarks:${id}`);
  } catch (_) {}
}

async function savePendingBookmarks(id) {
  const drafts = [...pendingFor(id)];
  for (const bookmark of drafts) {
    try {
      await api(`/api/notes/${id}/bookmarks/${bookmark.id}`, {method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(bookmark)});
      retainPending(id, pendingFor(id).filter((item) => item.id !== bookmark.id));
    } catch (_) { break; }
  }
  if (currentId === id) await loadBookmarks(id);
}

async function queueRecordingBookmarks(id, bookmarks) {
  if (!bookmarks.length) return;
  retainPending(id, bookmarks);
  await savePendingBookmarks(id);
}

async function loadBookmarks(id) {
  const version = ++bookmarkLoadVersion;
  try {
    const bookmarks = await api(`/api/notes/${id}/bookmarks`, {cache:'no-store'});
    if (currentId !== id || version !== bookmarkLoadVersion) return;
    savedBookmarks = bookmarks;
    bookmarkReadFailed = false;
    $('bookmark-status').textContent = pendingFor(id).length ? '저장하지 못한 북마크가 있습니다. 저장 다시 시도를 눌러 주세요.' : bookmarks.length ? `${bookmarks.length}개의 중요한 순간` : '재생 중 중요한 순간을 표시해 보세요.';
    renderBookmarks();
  } catch (error) {
    if (currentId !== id || version !== bookmarkLoadVersion) return;
    bookmarkReadFailed = true;
    $('bookmark-status').textContent = error.message;
    renderBookmarks();
  }
}

function renderBookmarks() {
  const panel = $('bookmark-list');
  panel.replaceChildren();
  const pending = pendingFor(currentId);
  const byId = new Map(savedBookmarks.map((item) => [item.id, item]));
  pending.forEach((item) => byId.set(item.id, item));
  [...byId.values()].sort((a, b) => a.positionMs - b.positionMs).forEach((bookmark) => {
    const chip = element('span', `bookmark-chip${pending.some((item) => item.id === bookmark.id) ? ' pending' : ''}`);
    const play = element('button', '', `☆ ${timeLabel(bookmark.positionMs)} ${bookmark.label}`);
    play.onclick = () => playSegment(currentId, bookmark.positionMs);
    const remove = element('button', 'remove-bookmark', '×');
    remove.setAttribute('aria-label', `${bookmark.label} 북마크 삭제`);
    remove.onclick = async () => {
      const id = currentId;
      remove.disabled = true;
      try {
        await api(`/api/notes/${id}/bookmarks/${bookmark.id}`, {method:'DELETE'});
        retainPending(id, pendingFor(id).filter((item) => item.id !== bookmark.id));
        if (currentId === id) { savedBookmarks = savedBookmarks.filter((item) => item.id !== bookmark.id); renderBookmarks(); }
      } catch (error) { message(error.message, true); remove.disabled = false; }
    };
    chip.append(play, remove); panel.append(chip);
  });
  $('retry-bookmarks').hidden = !pending.length && !bookmarkReadFailed;
  $('retry-bookmarks').textContent = pending.length ? '저장 다시 시도' : '다시 불러오기';
}

function resetWorkspace(id) {
  transcriptQuery = ''; searchPosition = -1; activeSequence = null;
  $('transcript-search').value = '';
  workspaceNoteId = id;
  savedBookmarks = [];
  bookmarkReadFailed = false;
  $('bookmark-label').value = '';
  $('bookmark-list').replaceChildren();
  $('bookmark-status').textContent = '북마크를 불러오는 중…';
  void loadBookmarks(id);
}

function displayWorkspace(note) {
  if (workspaceNoteId !== note.id) resetWorkspace(note.id);
  $('note-statistics').textContent = `${(note.segments || []).length}개 발언 · ${(note.speakers || []).length}명`;
}

async function loadSummarySettings() {
  try {
    summarySettings = await api('/api/settings/summary', {cache:'no-store'});
    $('summary-model').textContent = `${summarySettings.provider} · ${summarySettings.model}`;
    $('summary-setup').hidden = summarySettings.configured;
    $('summary-setup').textContent = `AI 요약을 연결하려면 서버에 ${summarySettings.keyVariable}를 설정하세요. 전사만 하려면 위 선택을 해제하세요.`;
  } catch (_) { $('summary-model').textContent = '요약 연결 상태 확인 필요'; }
}

function initWorkspace() {
  $('transcript-search').oninput = () => { transcriptQuery = $('transcript-search').value.trim(); searchPosition = -1; if (currentNote) renderTranscript(currentNote); };
  $('next-match').onclick = () => moveSearch(1);
  $('previous-match').onclick = () => moveSearch(-1);
  $('transcript-search').onkeydown = (event) => { if (event.key === 'Enter') { event.preventDefault(); moveSearch(event.shiftKey ? -1 : 1); } };
  $('playback-speed').onchange = () => { $('note-audio').playbackRate = Number($('playback-speed').value); };
  $('rewind-audio').onclick = () => { $('note-audio').currentTime = Math.max(0, $('note-audio').currentTime - 5); };
  $('forward-audio').onclick = () => { const audio = $('note-audio'); if (Number.isFinite(audio.duration)) audio.currentTime = Math.min(audio.duration, audio.currentTime + 5); };
  $('note-audio').addEventListener('timeupdate', syncPlayback);
  $('note-audio').addEventListener('loadedmetadata', () => { $('note-audio').playbackRate = Number($('playback-speed').value); });
  $('export-srt').onclick = exportSrt;
  $('copy-summary').onclick = async () => {
    try { await navigator.clipboard.writeText(currentNote.summary); message('요약을 복사했습니다.'); }
    catch (_) { message('클립보드에 접근할 수 없습니다. 요약 저장을 사용해 주세요.', true); }
  };
  $('export-summary').onclick = () => { if (currentNote?.summary) downloadText(`${safeNoteName()}-요약.md`, `# ${currentNote.title}\n\n${currentNote.summary}\n`, 'text/markdown;charset=utf-8'); };
  $('retry-bookmarks').onclick = async () => { const id = currentId; $('retry-bookmarks').disabled = true; try { await savePendingBookmarks(id); } finally { $('retry-bookmarks').disabled = false; } };
  $('add-bookmark').onclick = async () => {
    if (!currentId) return;
    const id = currentId;
    const bookmark = {id:crypto.randomUUID(), positionMs:Math.round($('note-audio').currentTime * 1000), label:$('bookmark-label').value.trim() || '중요한 순간'};
    retainPending(id, [...pendingFor(id), bookmark]);
    $('bookmark-label').value = '';
    renderBookmarks();
    $('add-bookmark').disabled = true;
    try { await savePendingBookmarks(id); } finally { $('add-bookmark').disabled = false; }
  };
  void loadSummarySettings();
}
