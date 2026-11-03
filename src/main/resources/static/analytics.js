(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const number = (value) => Number(value || 0).toLocaleString('ko-KR');
  const seconds = (ms) => `${(Number(ms) / 1000).toLocaleString('ko-KR', {maximumFractionDigits:1})}초`;
  const percent = (success, total) => total ? `${(success / total * 100).toFixed(1)}%` : '—';
  const stageName = (stage) => ({workflow:'전체 처리', transcription:'음성 전사', summary_request:'요약 요청'}[stage] || stage);
  const actionName = (action) => ({process:'최초 처리', retry:'재시도', regenerate:'요약 재생성'}[action] || action);
  const element = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  };
  function render(data) {
    const workflows = data.groups.filter((g) => g.stage === 'workflow');
    const summaries = data.groups.filter((g) => g.stage === 'summary_request');
    const sum = (rows, field) => rows.reduce((total, row) => total + Number(row[field] || 0), 0);
    const count = sum(workflows, 'calls');
    const usageCalls = sum(summaries, 'usage_calls');
    $('attempts').textContent = number(count);
    $('success-rate').textContent = percent(sum(workflows, 'succeeded'), count);
    $('failure-count').textContent = `실패 ${number(sum(workflows, 'failed'))}건`;
    $('average-time').textContent = count ? seconds(workflows.reduce((total, g) => total + g.avg_ms * g.calls, 0) / count) : '—';
    $('tokens').textContent = sum(summaries, 'token_calls') ? number(sum(summaries, 'input_tokens') + sum(summaries, 'output_tokens')) : '—';
    $('usage-coverage').textContent = `요약 ${number(sum(summaries, 'calls'))}회 중 ${number(usageCalls)}회에서 입력·출력 제공`;
    const daily = new Map(data.daily.map((row) => [row.date, row]));
    const rows = [];
    // A rolling N-day query can intersect N+1 UTC calendar dates.
    const today = new Date();
    for (let offset = 0; offset <= data.days; offset++) {
      const date = new Date(today);
      date.setUTCDate(date.getUTCDate() - offset);
      const key = date.toISOString().slice(0, 10);
      rows.push(daily.get(key) || {date:key, attempts:0, succeeded:0, failed:0});
    }
    const max = Math.max(1, ...rows.map((row) => Number(row.attempts)));
    $('daily-chart').replaceChildren(...rows.map((row) => {
      const container = element('div', undefined, 'daily-row');
      const track = element('div', undefined, 'daily-track');
      for (const kind of ['succeeded', 'failed']) {
        const bar = element('span', undefined, kind === 'succeeded' ? 'daily-success' : 'daily-failed');
        bar.style.width = `${Math.max(0, Math.min(100, Number(row[kind]) / max * 100))}%`;
        track.append(bar);
      }
      container.setAttribute('aria-label', `${row.date}: 성공 ${row.succeeded}건, 실패 ${row.failed}건`);
      container.title = container.getAttribute('aria-label');
      container.append(element('span', row.date.slice(5)), track, element('span', `${number(row.succeeded)} / ${number(row.failed)}`, 'daily-count'));
      return container;
    }));
    const models = data.groups.filter((g) => g.stage !== 'workflow');
    $('model-rows').replaceChildren(...models.map((row) => {
      const tr = element('tr');
      const model = element('td', stageName(row.stage));
      model.append(element('small', `${row.provider} / ${row.model}`));
      tr.append(model);
      for (const value of [actionName(row.action), number(row.calls), percent(row.succeeded, row.calls), seconds(row.avg_ms), seconds(row.p95_ms),
        row.input_usage_calls ? number(row.input_tokens) : '미제공', row.output_usage_calls ? number(row.output_tokens) : '미제공', `${number(row.usage_calls)} / ${number(row.calls)}회`]) {
        tr.append(element('td', value));
      }
      return tr;
    }));
    if (!models.length) {
      const tr = element('tr');
      const td = element('td', '아직 기록된 모델 호출이 없습니다.', 'empty-analytics');
      td.colSpan = 9;
      tr.append(td);
      $('model-rows').append(tr);
    }
    $('recent-failures').replaceChildren(...data.recentFailures.map((row) => {
      const item = element('div', undefined, 'failure-item');
      item.append(element('strong', `${stageName(row.stage)} · ${actionName(row.action)} · ${row.error_type}`),
        element('span', `${row.occurred_at} UTC · ${row.model || '전체 처리'}`),
        element('span', `노트 ${row.note_id} · 시도 ${row.attempt_id}`));
      return item;
    }));
    if (!data.recentFailures.length) $('recent-failures').append(element('p', '이 기간에 기록된 실패가 없습니다.', 'empty-analytics'));
    $('analytics-status').textContent = count ? `최근 ${data.days}일 · ${new Date().toLocaleTimeString('ko-KR')} 갱신` : '아직 처리 이력이 없습니다. 노트를 전사하거나 요약을 다시 생성하면 분석이 시작됩니다.';
  }
  async function load() {
    $('refresh').disabled = true;
    $('days').disabled = true;
    $('analytics-content').hidden = true;
    $('delivery-status').hidden = true;
    $('analytics-status').textContent = '분석 데이터를 불러오는 중입니다.';
    try {
      const response = await fetch(`/api/analytics?days=${encodeURIComponent($('days').value)}`, {cache:'no-store', signal:AbortSignal.timeout(15000)});
      const data = await response.json();
      if (typeof data.enabled !== 'boolean') throw new Error('Invalid response');
      const delivery = [];
      if (data.pendingEvents) delivery.push(`반영 대기 ${number(data.pendingEvents)}개`);
      if (data.droppedEvents) delivery.push(`저장 미확인 ${number(data.droppedEvents)}개 — 일부 이력이 집계에서 누락될 수 있습니다.`);
      $('delivery-status').textContent = delivery.join(' · ');
      $('delivery-status').hidden = !delivery.length;
      if (!data.enabled) { $('analytics-status').textContent = '처리 분석 수집이 꺼져 있습니다.'; return; }
      if (!response.ok || !data.available) { $('analytics-status').textContent = '분석 저장소에 연결할 수 없습니다. 잠시 후 새로고침해 주세요. 노트 처리는 계속 사용할 수 있습니다.'; return; }
      render(data);
      $('analytics-content').hidden = false;
    } catch (_) {
      $('analytics-status').textContent = '분석 데이터를 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요.';
    } finally {
      $('refresh').disabled = false;
      $('days').disabled = false;
    }
  }
  $('analytics-filter').addEventListener('submit', (event) => { event.preventDefault(); load(); });
  $('days').addEventListener('change', load);
  load();
})();
