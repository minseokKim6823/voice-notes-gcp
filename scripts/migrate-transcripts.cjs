// Optional, one-time copy from the previous Elasticsearch store. Existing MongoDB notes are preserved.
const {spawnSync} = require('node:child_process');

async function collectTranscripts(baseUrl, apiKey = '') {
  const headers = {'Content-Type':'application/json'};
  if (apiKey) headers.Authorization = `ApiKey ${apiKey}`;
  const request = async (method, path, body) => {
    const response = await fetch(`${baseUrl.replace(/\/+$/, '')}${path}`, {
      method, headers, body:JSON.stringify(body), signal:AbortSignal.timeout(15000)
    });
    if (!response.ok) throw new Error(`기존 전사 조회 실패 (HTTP ${response.status})`);
    return response.json();
  };
  let scrollId;
  const notes = new Map();
  try {
    let page = await request('POST', '/voice-note-segments/_search?scroll=1m', {size:500,sort:['_doc'],query:{match_all:{}}});
    while (true) {
      scrollId = page._scroll_id || scrollId;
      const hits = page.hits?.hits;
      if (!Array.isArray(hits)) throw new Error('기존 전사 응답 형식이 올바르지 않습니다.');
      if (!hits.length) break;
      for (const {_source:source} of hits) {
        if (!source || typeof source.noteId !== 'string' || !Number.isInteger(source.sequence) || typeof source.text !== 'string') {
          throw new Error('기존 전사 문서에 노트 ID, 발언 순서 또는 본문이 없습니다.');
        }
        if (!notes.has(source.noteId)) notes.set(source.noteId, {_id:source.noteId,segments:[]});
        notes.get(source.noteId).segments.push({sequence:source.sequence,speaker:source.speaker || '미확인',
          speakerName:source.speakerName || '',text:source.text,startMs:source.startMs || 0,endMs:source.endMs || 0});
      }
      if (!scrollId) throw new Error('전사 전체를 조회할 scroll ID가 없습니다.');
      page = await request('POST', '/_search/scroll', {scroll:'1m',scroll_id:scrollId});
    }
    for (const note of notes.values()) note.segments.sort((a, b) => a.sequence - b.sequence);
    return [...notes.values()];
  } finally {
    if (scrollId) await request('DELETE', '/_search/scroll', {scroll_id:[scrollId]}).catch(() => {});
  }
}

async function main() {
  const documents = await collectTranscripts(process.env.LEGACY_ELASTICSEARCH_URL || 'http://localhost:9202',
    process.env.LEGACY_ELASTICSEARCH_API_KEY || '');
  const segments = documents.reduce((count, note) => count + note.segments.length, 0);
  console.log(`기존 전사: 노트 ${documents.length}개, 발언 ${segments}개`);
  if (process.argv.includes('--dry-run') || !documents.length) return;
  const script = `
    const input = JSON.parse(require('fs').readFileSync(0, 'utf8'));
    const database = await connect(input.uri);
    const collection = database.getCollection('voice_note_transcripts');
    let inserted = 0;
    for (const document of input.documents) {
      const result = await collection.updateOne({_id:document._id}, {$setOnInsert:document}, {upsert:true});
      inserted += result.upsertedCount;
    }
    print(JSON.stringify({inserted,skipped:input.documents.length-inserted}));
  `;
  const result = spawnSync('docker', ['compose','exec','-T','mongodb','mongosh','--nodb','--quiet','--eval',script], {
    input:JSON.stringify({uri:process.env.MONGODB_URI || 'mongodb://localhost:27017/voice_notes',documents}),
    encoding:'utf8',windowsHide:true,timeout:120000,maxBuffer:1024*1024
  });
  if (result.error || result.status !== 0) throw new Error('MongoDB 이관 실패: Docker의 mongodb 서비스와 MONGODB_URI를 확인하세요. 기존 ES 데이터와 기존 MongoDB 문서는 유지됩니다.');
  console.log(result.stdout.trim());
}

module.exports = {collectTranscripts};
if (require.main === module) main().catch((error) => { console.error(error.message); process.exitCode = 1; });
