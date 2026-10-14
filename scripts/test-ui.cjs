// Runs the actual UI in headless Chrome with isolated API fixtures. No npm packages or cloud credentials required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');

const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'src/main/resources/static');
const output = path.join(root, 'build/ui-test');
fs.mkdirSync(output, {recursive:true});
const profile = fs.mkdtempSync(path.join(output, 'chrome-'));
const downloads = fs.mkdtempSync(path.join(output, 'downloads-'));
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const id = '11111111-1111-4111-8111-111111111111';
const otherId = '22222222-2222-4222-8222-222222222222';
const notes = new Map([
  [id, {id, title:'화자 기능 테스트', fileName:'meeting.wav', contentType:'audio/wav', createdAt:'2026-10-15T01:00:00Z', status:'READY', summary:'기존 요약', error:'', summaryStale:false,
    segments:[{sequence:0,speaker:'1',speakerName:'',text:'금요일 출시합니다.',startMs:0,endMs:1500},
      {sequence:1,speaker:'2',speakerName:'',text:'목요일까지 테스트하겠습니다.',startMs:1600,endMs:3000},
      {sequence:2,speaker:'1',speakerName:'',text:'감사합니다.',startMs:3500,endMs:4000}]}],
  [otherId, {id:otherId,title:'다른 노트',fileName:'other.wav',contentType:'audio/wav',createdAt:'2026-10-14T01:00:00Z',status:'READY',summary:'다른 요약',error:'',summaryStale:false,
    segments:[{sequence:0,speaker:'미확인',speakerName:'',text:'시간 정보가 없는 기존 전사',startMs:0,endMs:0}]}]
]);
const requests = [];
let failNext = false;
let holdNext = false;
let releaseEdit = null;
let holdNextRead = false;
let releaseRead = null;

function view(note) {
  const speakers = new Map();
  for (const segment of note.segments) {
    const name = segment.speakerName || (segment.speaker === '미확인' ? '미확인 화자' : `화자 ${segment.speaker}`);
    const speaker = speakers.get(segment.speaker) || {id:segment.speaker,name,segmentCount:0,durationMs:0};
    speaker.segmentCount++;
    speaker.durationMs += Math.max(0, segment.endMs - segment.startMs);
    speakers.set(speaker.id, speaker);
  }
  return {...note, speakers:[...speakers.values()], transcript:note.segments.map((s) => `${speakers.get(s.speaker).name}: ${s.text}`).join('\n')};
}

const wav = Buffer.alloc(44 + 8000 * 2 * 5);
wav.write('RIFF'); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8);
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(8000, 24); wav.writeUInt32LE(16000, 28); wav.writeUInt16LE(2, 32);
wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(wav.length - 44, 40);

const server = http.createServer(async (req, res) => {
  try {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    const send = (status, data) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(data)); };
    if (!pathname.startsWith('/api/')) {
      const file = path.resolve(staticRoot, pathname === '/' ? 'index.html' : `.${pathname}`);
      if (!file.startsWith(staticRoot + path.sep) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
      const mime = {'.html':'text/html','.js':'text/javascript','.css':'text/css'}[path.extname(file)];
      if (!mime) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, {'Content-Type':`${mime};charset=utf-8`}); res.end(fs.readFileSync(file)); return;
    }
    if (pathname === '/api/notes') { send(200, [...notes.values()].map(view)); return; }
    const parts = pathname.split('/').map(decodeURIComponent);
    const note = notes.get(parts[3]);
    if (!note) { send(404, {error:'노트를 찾을 수 없습니다.'}); return; }
    if (parts[4] === 'audio') { res.writeHead(200, {'Content-Type':'audio/wav','Accept-Ranges':'bytes'}); res.end(wav); return; }
    if (req.method === 'GET') {
      if (holdNextRead) { holdNextRead = false; await new Promise((resolve) => { releaseRead = resolve; }); releaseRead = null; }
      send(200, view(note)); return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString();
    const body = raw ? JSON.parse(raw) : {};
    requests.push({method:req.method,pathname,body});
    if (failNext) {
      failNext = false;
      if (parts[4] === 'summary') { note.status = 'FAILED'; note.error = '테스트 저장 오류'; note.summaryStale = true; }
      send(502, {error:'테스트 저장 오류'}); return;
    }
    if (holdNext) { holdNext = false; await new Promise((resolve) => { releaseEdit = resolve; }); releaseEdit = null; }
    if (parts[4] === 'speakers' && parts[6] === 'merge') {
      const target = note.segments.find((s) => s.speaker === body.targetSpeaker);
      for (const segment of note.segments) if (segment.speaker === parts[5]) { segment.speaker = target.speaker; segment.speakerName = target.speakerName; }
    } else if (parts[4] === 'speakers') {
      for (const segment of note.segments) if (segment.speaker === parts[5]) segment.speakerName = body.name.trim();
    } else if (parts[4] === 'segments') {
      const segment = note.segments.find((s) => s.sequence === Number(parts[5]));
      if (parts[6] === 'speaker') {
        if (body.name) { segment.speaker = 'custom-ui'; segment.speakerName = body.name.trim(); }
        else { const target = note.segments.find((s) => s.speaker === body.speaker); segment.speaker = target.speaker; segment.speakerName = target.speakerName; }
      } else segment.text = body.text.trim();
    } else if (parts[4] === 'summary') {
      note.summary = `수정 요약: ${view(note).speakers.map((s) => s.name).join(', ')}`;
      note.summaryStale = false;
      note.status = 'READY';
      note.error = '';
      send(200, view(note)); return;
    } else { send(404, {error:'잘못된 테스트 요청'}); return; }
    note.summaryStale = true;
    send(200, view(note));
  } catch (error) { res.writeHead(500); res.end(JSON.stringify({error:error.message})); }
});

async function until(check, label, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await check()) return; await delay(100); }
  throw new Error(`Timed out: ${label}`);
}

async function main() {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const executable = process.env.VOICE_NOTES_CHROME || (process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : 'google-chrome');
  const chrome = spawn(executable, ['--headless=new', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--autoplay-policy=no-user-gesture-required', 'about:blank'], {stdio:'ignore', windowsHide:true});
  let launchError;
  chrome.on('error', (error) => { launchError = error; });
  let socket;
  try {
    const portFile = path.join(profile, 'DevToolsActivePort');
    await until(() => { if (launchError) throw launchError; return fs.existsSync(portFile); }, 'Chrome startup', 30000);
    const port = fs.readFileSync(portFile, 'utf8').split('\n')[0];
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    socket = new WebSocket(targets.find((target) => target.type === 'page').webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once:true}); socket.addEventListener('error', reject, {once:true}); });
    let nextId = 0;
    const pending = new Map();
    const exceptions = [];
    socket.addEventListener('message', ({data}) => {
      const event = JSON.parse(data);
      if (event.method === 'Runtime.exceptionThrown') exceptions.push(event.params.exceptionDetails.text);
      const request = pending.get(event.id);
      if (request) { pending.delete(event.id); clearTimeout(request.timer); event.error ? request.reject(new Error(event.error.message)) : request.resolve(event.result); }
    });
    const cdp = (method, params = {}) => new Promise((resolve, reject) => {
      const requestId = ++nextId;
      const timer = setTimeout(() => { pending.delete(requestId); reject(new Error(`CDP timeout: ${method}`)); }, 15000);
      pending.set(requestId, {resolve,reject,timer});
      socket.send(JSON.stringify({id:requestId,method,params}));
    });
    const evaluate = async (fn, ...args) => {
      const result = await cdp('Runtime.evaluate', {expression:`(${fn.toString()})(...${JSON.stringify(args)})`,awaitPromise:true,returnByValue:true,userGesture:true});
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
      return result.result.value;
    };
    const waitUI = (fn, label) => until(() => evaluate(fn), label);
    await cdp('Page.enable');
    await cdp('Runtime.enable');
    await cdp('Browser.setDownloadBehavior', {behavior:'allow',downloadPath:downloads});
    await cdp('Emulation.setDeviceMetricsOverride', {width:1440,height:1080,deviceScaleFactor:1,mobile:false});
    await cdp('Page.navigate', {url:`http://127.0.0.1:${server.address().port}`});
    await waitUI(() => document.querySelectorAll('.list-item').length === 2, 'note list');
    await evaluate((noteId) => document.querySelector(`[data-id="${noteId}"]`).click(), id);
    await waitUI(() => document.querySelectorAll('.speaker-card').length === 2, 'speaker cards');
    assert.equal(await evaluate(() => document.querySelectorAll('.transcript-segment').length), 3);
    assert.match(await evaluate(() => document.querySelector('.speaker-stats').textContent), /2개 발언/);
    console.log('PASS: speaker cards, statistics and transcript rendering');

    await evaluate(() => document.querySelector('[data-sequence="1"] .segment-time').click());
    await waitUI(() => document.getElementById('note-audio').currentTime >= 1.6, 'segment playback');
    await evaluate(() => document.getElementById('note-audio').pause());
    const timeBefore = await evaluate(() => document.getElementById('note-audio').currentTime);
    const renamed = '<img src=x onerror=alert(1)>민수';
    await evaluate((name) => { const form = document.querySelector('.speaker-rename'); form.querySelector('input').value = name; form.requestSubmit(); }, renamed);
    await waitUI(() => !document.getElementById('summary-stale').hidden && !document.querySelector('.speaker-rename button').disabled, 'rename saved');
    assert.equal(await evaluate(() => document.querySelectorAll('#speaker-panel img, #note-transcript img').length), 0);
    assert.equal(await evaluate(() => document.querySelector('.segment-speaker').textContent), renamed);
    assert.equal(await evaluate(() => document.getElementById('note-audio').currentTime), timeBefore);
    console.log('PASS: rename persistence, safe name rendering, stale summary and playback preservation');

    await evaluate(() => { const select = document.getElementById('speaker-filter'); select.value = '2'; select.dispatchEvent(new Event('change')); });
    assert.equal(await evaluate(() => document.querySelectorAll('.transcript-segment').length), 1);
    await evaluate(() => document.getElementById('export-button').click());
    await until(() => fs.readdirSync(downloads).some((file) => file.endsWith('.txt')), 'filtered export');
    const exported = fs.readFileSync(path.join(downloads, fs.readdirSync(downloads).find((file) => file.endsWith('.txt'))), 'utf8');
    assert.match(exported, /목요일까지 테스트하겠습니다/);
    assert.doesNotMatch(exported, /금요일 출시합니다/);
    console.log('PASS: speaker filter and UTF-8 filtered text export');

    await evaluate(() => { const select = document.getElementById('speaker-filter'); select.value = ''; select.dispatchEvent(new Event('change')); });
    await evaluate(() => {
      const form = document.querySelector('[data-sequence="0"] .segment-assignment');
      const select = form.querySelector('select'); select.value = '__new__'; select.dispatchEvent(new Event('change'));
      form.querySelector('input').value = '새 참가자'; form.requestSubmit();
    });
    await waitUI(() => document.querySelectorAll('.speaker-card').length === 3 && !document.querySelector('.speaker-rename button').disabled, 'new speaker');
    assert.equal(notes.get(id).segments[0].speaker, 'custom-ui');
    await evaluate(() => { const form = document.querySelector('.speaker-merge'); form.querySelector('select').value = '2'; form.requestSubmit(); });
    await waitUI(() => document.querySelectorAll('.speaker-card').length === 2 && !document.querySelector('.speaker-rename button').disabled, 'speaker merge');
    assert.equal(notes.get(id).segments[0].speaker, '2');
    assert.equal(await evaluate(() => document.querySelectorAll('.transcript-segment').length), 3);
    console.log('PASS: split utterance into new speaker and merge speakers');

    await evaluate(() => { const form = document.querySelector('[data-sequence="1"] .segment-text-editor'); form.querySelector('textarea').value = '수정한 발언입니다.'; form.requestSubmit(); });
    await waitUI(() => document.querySelector('[data-sequence="1"] .segment-text').textContent === '수정한 발언입니다.' && !document.getElementById('summary-button').disabled, 'transcript edit');
    await evaluate(() => document.getElementById('summary-button').click());
    await waitUI(() => document.getElementById('summary-stale').hidden && !document.getElementById('summary-button').disabled, 'summary regeneration');
    assert.match(await evaluate(() => document.getElementById('note-summary').textContent), /수정 요약/);
    assert.equal(requests.filter((request) => request.pathname.endsWith('/process')).length, 0);
    console.log('PASS: transcript correction and summary regeneration without retranscription');

    failNext = true;
    await evaluate(() => { const form = document.querySelector('.speaker-rename'); form.querySelector('input').value = '실패할 이름'; form.requestSubmit(); });
    await waitUI(() => document.getElementById('message').textContent === '테스트 저장 오류' && !document.querySelector('.speaker-rename button').disabled, 'failed save recovery');
    assert.notEqual(await evaluate(() => document.querySelector('.speaker-card strong').textContent), '실패할 이름');
    console.log('PASS: failed-save message and restored editing controls');

    holdNext = true;
    await evaluate(() => { const form = document.querySelector('.speaker-rename'); form.querySelector('input').value = '늦게 저장한 이름'; form.requestSubmit(); });
    await until(() => releaseEdit !== null, 'pending edit');
    assert.equal(await evaluate(() => document.getElementById('summary-button').disabled), true);
    await evaluate((noteId) => document.querySelector(`[data-id="${noteId}"]`).click(), otherId);
    await waitUI(() => document.getElementById('note-title').textContent === '다른 노트', 'navigate during save');
    releaseEdit();
    await delay(300);
    assert.equal(await evaluate(() => document.getElementById('note-title').textContent), '다른 노트');
    assert.notEqual(await evaluate(() => document.getElementById('message').textContent), '변경 내용을 저장하는 중입니다…');
    assert.equal(await evaluate(() => document.querySelector('.segment-time').disabled), true);
    assert.match(await evaluate(() => document.querySelector('.speaker-card strong').textContent), /미확인 화자/);
    await evaluate((noteId) => document.querySelector(`[data-id="${noteId}"]`).click(), id);
    await waitUI(() => document.querySelector('.speaker-card strong')?.textContent === '늦게 저장한 이름', 'reopen saved note');
    console.log('PASS: navigation during save, reopening edits and missing-timing fallback');

    failNext = true;
    holdNextRead = true;
    await evaluate(() => { const form = document.querySelector('.speaker-rename'); form.querySelector('input').value = '오류 복구 중 이동'; form.requestSubmit(); });
    await until(() => releaseRead !== null, 'pending error refresh');
    await evaluate((noteId) => document.querySelector(`[data-id="${noteId}"]`).click(), otherId);
    await waitUI(() => document.getElementById('note-title').textContent === '다른 노트', 'navigate during error refresh');
    releaseRead();
    await delay(300);
    await evaluate(() => document.getElementById('speaker-filter').dispatchEvent(new Event('change')));
    assert.match(await evaluate(() => document.getElementById('note-transcript').textContent), /시간 정보가 없는 기존 전사/);
    assert.equal(await evaluate(() => document.getElementById('note-title').textContent), '다른 노트');
    console.log('PASS: navigation during failed-save refresh preserves the selected note');

    await evaluate((noteId) => document.querySelector(`[data-id="${noteId}"]`).click(), id);
    await waitUI(() => document.getElementById('note-title').textContent === '화자 기능 테스트', 'reopen before summary failure');
    failNext = true;
    await evaluate(() => document.getElementById('summary-button').click());
    await waitUI(() => document.getElementById('note-status').textContent === '처리 실패' && !document.getElementById('summary-button').disabled, 'summary failure recovery');
    assert.equal(await evaluate(() => document.getElementById('retry-button').hidden), false);
    assert.equal(await evaluate(() => document.getElementById('retry-button').disabled), false);
    await evaluate(() => document.getElementById('summary-button').click());
    await waitUI(() => document.getElementById('note-status').textContent === '완료' && !document.getElementById('summary-button').disabled, 'summary retry');
    console.log('PASS: failed summary restores controls and can be regenerated');

    await cdp('Page.captureScreenshot', {format:'png',captureBeyondViewport:true}).then((result) => fs.writeFileSync(path.join(output, 'desktop.png'), Buffer.from(result.data, 'base64')));
    await cdp('Emulation.setDeviceMetricsOverride', {width:390,height:844,deviceScaleFactor:1,mobile:true});
    assert.equal(await evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await cdp('Page.captureScreenshot', {format:'png',captureBeyondViewport:true}).then((result) => fs.writeFileSync(path.join(output, 'mobile.png'), Buffer.from(result.data, 'base64')));
    assert.deepEqual(exceptions, []);
    console.log('PASS: mobile layout and no uncaught browser exceptions');
    await cdp('Browser.close').catch(() => {});
    console.log('UI tests passed (11 workflows). Screenshots: build/ui-test/desktop.png, mobile.png');
  } finally {
    if (releaseEdit) releaseEdit();
    if (releaseRead) releaseRead();
    if (socket) socket.close();
    chrome.kill();
    await new Promise((resolve) => server.close(resolve));
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
