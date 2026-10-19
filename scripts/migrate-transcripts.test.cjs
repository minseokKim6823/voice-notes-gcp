const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {collectTranscripts} = require('./migrate-transcripts.cjs');

test('scrolls all legacy segments, preserves edited names, and groups them in sequence order', async () => {
  let page = 0;
  let cleared = false;
  const server = http.createServer((req, res) => {
    assert.equal(req.headers.authorization, 'ApiKey test-only');
    res.setHeader('Content-Type', 'application/json');
    if (req.method === 'DELETE') { cleared = true; res.end('{}'); return; }
    const segments = [
      [{noteId:'note',sequence:5,speaker:'custom-1',speakerName:'민수',text:'수정 발언 👍',startMs:1250,endMs:4000}],
      [{noteId:'note',sequence:0,speaker:'1',text:'기존 발언'}], []
    ][page++];
    res.end(JSON.stringify({_scroll_id:'cursor',hits:{hits:segments.map((_source) => ({_source}))}}));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const documents = await collectTranscripts(`http://127.0.0.1:${server.address().port}`, 'test-only');
    assert.deepEqual(documents, [{_id:'note',segments:[
      {sequence:0,speaker:'1',speakerName:'',text:'기존 발언',startMs:0,endMs:0},
      {sequence:5,speaker:'custom-1',speakerName:'민수',text:'수정 발언 👍',startMs:1250,endMs:4000}
    ]}]);
    assert.equal(cleared, true);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});
