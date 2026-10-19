let speakerFilter = '';
const editingIds = new Set();

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function timeLabel(ms) {
  const seconds = Math.floor(Math.max(0, ms) / 1000);
  const minutes = Math.floor(seconds / 60);
  const tenths = Math.floor(Math.max(0, ms) % 1000 / 100);
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}${tenths ? `.${tenths}` : ''}`;
}

function speakerSelect(speakers, selected, label, exclude = null) {
  const select = element('select', 'speaker-select');
  select.setAttribute('aria-label', label);
  for (const speaker of speakers) {
    if (speaker.id === exclude) continue;
    const option = element('option', '', speaker.name);
    option.value = speaker.id;
    option.selected = speaker.id === selected;
    select.append(option);
  }
  return select;
}

function editable(note) {
  return note.status !== 'PROCESSING' && !processingIds.has(note.id) && !editingIds.has(note.id);
}

function lockSpeakerControls() {
  for (const control of document.querySelectorAll('#speaker-panel input, #speaker-panel button, #speaker-panel select, #note-transcript input, #note-transcript textarea, #note-transcript select, #note-transcript button, #summary-button, #retry-button')) {
    control.disabled = true;
  }
}

async function editNote(note, path, method, body, success) {
  if (!editable(note)) return;
  const version = selectionVersion;
  editingIds.add(note.id);
  let saved = false;
  lockSpeakerControls();
  message('변경 내용을 저장하는 중입니다…');
  try {
    const updated = await api(`/api/notes/${note.id}${path}`, {
      method, headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
    });
    saved = true;
    editingIds.delete(note.id);
    if (currentId === note.id && selectionVersion === version) {
      display(updated);
      message(success);
    }
  } catch (error) {
    message(error.message, true);
    // Reload the last stored state after a failed save.
    if (currentId === note.id && selectionVersion === version) {
      try {
        const refreshed = await api(`/api/notes/${note.id}`);
        if (currentId === note.id && selectionVersion === version) currentNote = refreshed;
      } catch (_) {}
    }
  } finally {
    editingIds.delete(note.id);
    if (currentId === note.id && selectionVersion === version && currentNote) display(currentNote);
    else if (saved && !editingIds.has(currentId) && $('message').textContent === '변경 내용을 저장하는 중입니다…') message(success);
  }
}

function displaySpeakers(note) {
  const speakers = note.speakers || [];
  const segments = note.segments || [];
  const enabled = editable(note);
  $('speaker-panel').hidden = !speakers.length;
  $('speaker-count').textContent = `${speakers.length}명`;
  $('speaker-list').replaceChildren();
  speakers.forEach((speaker, index) => {
    const card = element('div', 'speaker-card');
    card.style.setProperty('--speaker-color', speakerColor(index));
    const heading = element('div', 'speaker-card-heading');
    heading.append(element('span', 'speaker-dot'), element('strong', '', speaker.name));
    card.append(heading, element('p', 'speaker-stats', `${speaker.segmentCount}개 발언 · ${speaker.durationMs > 0 ? timeLabel(speaker.durationMs) : '시간 정보 없음'}`));
    const rename = element('form', 'speaker-rename');
    const input = element('input');
    input.type = 'text';
    input.value = speaker.name;
    input.maxLength = 60;
    input.required = true;
    input.setAttribute('aria-label', `${speaker.name} 이름`);
    const save = element('button', 'small-button', '이름 저장');
    save.type = 'submit';
    input.disabled = save.disabled = !enabled;
    rename.append(input, save);
    rename.onsubmit = (event) => {
      event.preventDefault();
      editNote(note, `/speakers/${encodeURIComponent(speaker.id)}`, 'PATCH', {name:input.value}, '화자 이름을 저장했습니다.');
    };
    card.append(rename);
    if (speakers.length > 1) {
      const merge = element('form', 'speaker-merge');
      const target = speakerSelect(speakers, null, `${speaker.name}을 합칠 화자`, speaker.id);
      const button = element('button', 'small-button', '이 화자로 합치기');
      button.type = 'submit';
      target.disabled = button.disabled = !enabled;
      merge.append(target, button);
      merge.onsubmit = (event) => {
        event.preventDefault();
        editNote(note, `/speakers/${encodeURIComponent(speaker.id)}/merge`, 'POST', {targetSpeaker:target.value}, '선택한 화자로 모든 발언을 합쳤습니다.');
      };
      card.append(merge);
    }
    $('speaker-list').append(card);
  });
  if (!speakers.some((speaker) => speaker.id === speakerFilter)) speakerFilter = '';
  const filter = $('speaker-filter');
  filter.replaceChildren(element('option', '', '전체 화자'));
  filter.firstChild.value = '';
  for (const speaker of speakers) {
    const option = element('option', '', `${speaker.name} (${speaker.segmentCount})`);
    option.value = speaker.id;
    filter.append(option);
  }
  filter.value = speakerFilter;
  filter.disabled = !speakers.length;
  $('summary-stale').hidden = !note.summaryStale;
  $('summary-button').hidden = !segments.length;
  $('summary-button').disabled = !enabled;
  $('summary-button').textContent = note.summary ? '요약 다시 생성' : '요약 생성';
  $('export-button').disabled = !segments.length;
  renderTranscript(note);
}

function speakerColor(index) {
  return ['#32795b', '#476bac', '#ab6539', '#8656a6', '#287c85', '#a34265'][index % 6];
}

function renderTranscript(note) {
  const list = $('note-transcript');
  list.replaceChildren();
  const speakers = note.speakers || [];
  const segments = (note.segments || []).filter((segment) => !speakerFilter || segment.speaker === speakerFilter);
  if (!segments.length) {
    list.append(element('p', 'body-text', note.transcript || '전사 결과가 아직 없습니다.'));
    return;
  }
  for (const segment of segments) {
    const speaker = speakers.find((entry) => entry.id === segment.speaker);
    const name = speaker?.name || segment.speakerName || `화자 ${segment.speaker}`;
    const row = element('div', 'transcript-segment');
    row.dataset.sequence = segment.sequence;
    row.style.setProperty('--speaker-color', speakerColor(Math.max(0, speakers.indexOf(speaker))));
    const heading = element('div', 'segment-heading');
    const play = element('button', 'segment-time', segment.endMs > segment.startMs
      ? `▶ ${timeLabel(segment.startMs)} – ${timeLabel(segment.endMs)}` : '시간 정보 없음');
    play.type = 'button';
    play.disabled = segment.endMs <= segment.startMs;
    play.setAttribute('aria-label', `${name} 발언 ${timeLabel(segment.startMs)}부터 재생`);
    play.onclick = () => playSegment(note.id, segment.startMs);
    heading.append(element('strong', 'segment-speaker', name), play);
    row.append(heading, element('p', 'segment-text', segment.text));
    const details = element('details', 'segment-editor');
    details.append(element('summary', '', '화자 / 발언 편집'));
    const assignment = element('form', 'segment-assignment');
    const select = speakerSelect(speakers, segment.speaker, `${segment.sequence + 1}번째 발언의 화자`);
    const newOption = element('option', '', '＋ 새 화자로 분리');
    newOption.value = '__new__';
    select.append(newOption);
    const newName = element('input');
    newName.type = 'text';
    newName.placeholder = '새 화자 이름';
    newName.maxLength = 60;
    newName.hidden = true;
    newName.setAttribute('aria-label', '새 화자 이름');
    select.onchange = () => {
      newName.hidden = select.value !== '__new__';
      newName.required = !newName.hidden;
      if (!newName.hidden) newName.focus();
    };
    const move = element('button', 'small-button', '화자 저장');
    move.type = 'submit';
    select.disabled = newName.disabled = move.disabled = !editable(note);
    assignment.append(select, newName, move);
    assignment.onsubmit = (event) => {
      event.preventDefault();
      const body = select.value === '__new__' ? {name:newName.value} : {speaker:select.value};
      editNote(note, `/segments/${segment.sequence}/speaker`, 'PATCH', body, '발언의 화자를 저장했습니다.');
    };
    const textForm = element('form', 'segment-text-editor');
    const text = element('textarea');
    text.value = segment.text;
    text.rows = 3;
    text.required = true;
    text.maxLength = 5000;
    text.setAttribute('aria-label', `${segment.sequence + 1}번째 발언 내용`);
    const saveText = element('button', 'small-button', '발언 내용 저장');
    saveText.type = 'submit';
    text.disabled = saveText.disabled = !editable(note);
    textForm.append(text, saveText);
    textForm.onsubmit = (event) => {
      event.preventDefault();
      editNote(note, `/segments/${segment.sequence}`, 'PATCH', {text:text.value}, '발언 내용을 저장했습니다.');
    };
    details.append(assignment, textForm);
    row.append(details);
    list.append(row);
  }
}

async function playSegment(noteId, startMs) {
  const audio = $('note-audio');
  try {
    if (audio.readyState === 0) {
      await new Promise((resolve, reject) => {
        const cleanup = () => {
          audio.removeEventListener('loadedmetadata', loaded);
          audio.removeEventListener('error', failed);
        };
        const loaded = () => { cleanup(); resolve(); };
        const failed = () => { cleanup(); reject(new Error('원본 음성을 불러올 수 없습니다.')); };
        audio.addEventListener('loadedmetadata', loaded, {once:true});
        audio.addEventListener('error', failed, {once:true});
        audio.load();
      });
    }
    if (currentId !== noteId) return;
    audio.currentTime = startMs / 1000;
    await audio.play();
  } catch (error) { message(error.message, true); }
}

function exportTranscript() {
  if (!currentNote) return;
  const note = currentNote;
  const segments = note.segments.filter((segment) => !speakerFilter || segment.speaker === speakerFilter);
  const lines = segments.map((segment) => {
    const name = note.speakers.find((speaker) => speaker.id === segment.speaker)?.name || segment.speakerName || `화자 ${segment.speaker}`;
    const time = segment.endMs > segment.startMs ? `[${timeLabel(segment.startMs)} – ${timeLabel(segment.endMs)}] ` : '';
    return `${time}${name}: ${segment.text}`;
  });
  const content = `${note.title}\n${new Date(note.createdAt).toLocaleString('ko-KR')}\n\n${lines.join('\n\n')}\n`;
  const url = URL.createObjectURL(new Blob(['\uFEFF', content], {type:'text/plain;charset=utf-8'}));
  const link = element('a');
  link.href = url;
  link.download = `${note.title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 100) || '전사'}${speakerFilter ? '-화자별' : ''}.txt`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  message(speakerFilter ? '선택한 화자의 전사를 저장했습니다.' : '전체 전사를 저장했습니다.');
}
