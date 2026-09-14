// 接口健壮性回归检查（不需要浏览器，只需要 Node + java）。
// 覆盖：文件不存在返回 404（而不是 500）、试填未完成返回 409、分页参数越界不再报数据库错误、
//       SCAN_COMMAND 支持"可执行文件 + 参数"。
// 用法：mvn package && node scripts/test-api-guards.cjs
// 使用内存库与临时存储目录，不会访问项目的 data 目录。
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');
const { mkdtempSync, rmSync, readFileSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const { randomUUID } = require('node:crypto');

const port = 18082;
const base = `http://127.0.0.1:${port}/api/v1`;
const storage = mkdtempSync(join(tmpdir(), 'qiheng-guards-'));
const password = 'Temp-' + randomUUID();
const newPassword = 'Changed-' + randomUUID();
const sleep = ms => new Promise(r => setTimeout(r, ms));

const jar = {};
const cookieHeader = () => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
function absorb(res) {
  const list = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
  for (const c of list) { const pair = c.split(';')[0], i = pair.indexOf('='); jar[pair.slice(0, i)] = pair.slice(i + 1); }
}
function headers(extra = {}) { return { cookie: cookieHeader(), 'X-CSRF-Token': jar.QH_CSRF || '', ...extra }; }
async function call(path, { method = 'GET', json, form } = {}) {
  const h = headers(json || form ? (form ? {} : { 'content-type': 'application/json' }) : {});
  const res = await fetch(base + path, { method, headers: h, body: form || (json ? JSON.stringify(json) : undefined) });
  absorb(res);
  const type = res.headers.get('content-type') || '';
  const body = type.includes('json') ? await res.json().catch(() => null) : null;
  return { status: res.status, body, res };
}

let failures = 0;
function check(name, cond, extra) { console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : '  <-- ' + (extra || ''))); if (!cond) failures++; }

async function main() {
  const server = spawn('java', [
    // 让重定向出来的日志按 UTF-8 输出，方便断言中文提示
    '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-jar', resolve('target/ContractHub-1.0.0.jar'),
    `--server.port=${port}`,
    '--spring.datasource.url=jdbc:h2:mem:guardtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
    '--app.worker-enabled=false',
    '--app.storage=' + storage,
    // 故意配成"可执行文件 + 参数"：旧实现会把整串当成文件名，上传必然失败
    '--app.scan-command=cmd /c exit 0',
    '--init-admin', '--username=guard_admin', '--display-name=测试管理员', '--password=' + password],
    { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let logs = '';
  server.stdout.on('data', d => { logs += d.toString(); });
  server.stderr.on('data', d => { logs += d.toString(); });
  try {
    // 用健康检查判断就绪（比匹配日志文本稳），启动失败时把日志尾部带出来
    let ready = false;
    for (let i = 0; i < 120; i++) {
      if (server.exitCode !== null) throw new Error('测试服务启动失败：' + logs.slice(-1500));
      try { const h = await fetch(`http://127.0.0.1:${port}/api/v1/health`); if (h.status === 200) { ready = true; break; } } catch { }
      await sleep(250);
    }
    assert(ready, '服务健康检查超时：' + logs.slice(-800));
    assert(logs.includes('管理员初始化完成'), '管理员未初始化：' + logs.slice(-800));

    let r = await call('/auth/csrf'); assert.equal(r.status, 200, 'csrf');
    r = await call('/auth/login', { method: 'POST', json: { username: 'guard_admin', password } });
    assert.equal(r.status, 200, 'login: ' + r.status + ' ' + JSON.stringify(r.body));
    r = await call('/auth/change-password', { method: 'POST', json: { old_password: password, new_password: newPassword } });
    check('首次登录改密成功', r.status === 200, r.status + ' ' + JSON.stringify(r.body));

    // 建模板 + 草稿版本
    r = await call('/templates', { method: 'POST', json: { code: 'GUARD1', name: '健壮性验证', category: '测试' } });
    check('新建模板', r.status === 200, r.status + ' ' + JSON.stringify(r.body));
    const templateId = r.body.id;
    r = await call(`/templates/${templateId}/versions`, { method: 'POST', json: { change_note: 'guard' } });
    check('创建版本', r.status === 200, r.status + ' ' + JSON.stringify(r.body));
    const versionId = r.body.id;

    // 1) 还没上传母版：下载/查看内部文件必须是 404，不是 500
    r = await call(`/template-versions/${versionId}/source?format=docx`);
    check('未上传母版时下载 DOCX → 404', r.status === 404 && r.body?.code === 'NOT_FOUND', r.status + ' ' + JSON.stringify(r.body));
    r = await call(`/template-versions/${versionId}/source?format=pdf`);
    check('未上传 PDF 时下载 PDF → 404', r.status === 404 && r.body?.code === 'NOT_FOUND', r.status + ' ' + JSON.stringify(r.body));
    r = await call(`/template-versions/${versionId}/source?format=original`);
    check('没有原始上传件时 → 404', r.status === 404, r.status + ' ' + JSON.stringify(r.body));

    // 2) 没试填过：查看试填 PDF 必须是 409，不是 500
    r = await call(`/template-versions/${versionId}/test-preview`);
    check('未试填时查看试填 PDF → 409', r.status === 409 && r.body?.code === 'TEST_NOT_READY', r.status + ' ' + JSON.stringify(r.body));

    // 3) 没上传母版时校验：报的是"先上传母版"这种业务错误，不是 500
    r = await call(`/template-versions/${versionId}/validate`, { method: 'POST', json: {} });
    check('无母版时校验 → 422 DOCX_REQUIRED', r.status === 422 && r.body?.code === 'DOCX_REQUIRED', r.status + ' ' + JSON.stringify(r.body));

    // 4) 上传母版：SCAN_COMMAND 配了参数也要能通过（旧实现会 422/500）
    const file = readFileSync(resolve('samples/示例合同-测试数据.docx'));
    const form = new FormData();
    form.append('file', new Blob([file]), '示例合同-测试数据.docx');
    form.append('expected_lock_version', '0');
    r = await call(`/template-versions/${versionId}`);
    const lock = r.body.lock_version;
    const form2 = new FormData();
    form2.append('file', new Blob([file]), '示例合同-测试数据.docx');
    form2.append('expected_lock_version', String(lock));
    r = await call(`/template-versions/${versionId}/files`, { method: 'POST', form: form2 });
    check('带参数的安全扫描命令下上传母版成功', r.status === 200, r.status + ' ' + JSON.stringify(r.body));

    // 上传成功后：DOCX 可下载，PDF 仍然应该是 404
    r = await call(`/template-versions/${versionId}/source?format=docx`);
    check('已上传母版后下载 DOCX → 200', r.status === 200, r.status + ' ' + JSON.stringify(r.body));
    r = await call(`/template-versions/${versionId}/source?format=pdf`);
    check('仍无 PDF 时下载 PDF → 404', r.status === 404, r.status + ' ' + JSON.stringify(r.body));

    // 5) 分页参数越界：夹紧而不是抛数据库错误
    r = await call('/audit-logs?page=2147483647&size=100');
    check('审计日志超大页码 → 200', r.status === 200 && r.body.page === 1000000, r.status + ' ' + JSON.stringify(r.body));
    r = await call('/audit-logs?page=-3&size=999999');
    check('审计日志负数页码/超大 size → 200 且被夹紧', r.status === 200 && r.body.page === 1 && r.body.size === 200, r.status + ' ' + JSON.stringify(r.body));
    r = await call('/contracts?page=-5&size=100000');
    check('合同列表越界分页 → 200 且被夹紧', r.status === 200 && r.body.page === 1 && r.body.size === 100, r.status + ' ' + JSON.stringify(r.body));
    r = await call('/templates?page=2147483647&size=1');
    check('模板列表超大页码 → 200', r.status === 200 && r.body.page === 1000000, r.status + ' ' + JSON.stringify(r.body));

    // 6) 不存在的对象仍是干净的 404
    r = await call('/contracts/does-not-exist');
    check('不存在的合同 → 404', r.status === 404, r.status + ' ' + JSON.stringify(r.body));

    // 7) 首页样式：禁用按钮不能是"忙"光标（分页上一页/下一页在首末页是 disabled，
    //    用 cursor:wait 会让鼠标一直转圈，看起来像卡死）
    const css = await (await fetch(`http://127.0.0.1:${port}/app.css`)).text();
    check('禁用的按钮用 not-allowed 而不是 wait', /button:disabled\{[^}]*cursor:not-allowed/.test(css) && !/button:disabled\{[^}]*cursor:wait/.test(css), css.match(/button:disabled\{[^}]*\}/)?.[0]);
    check('提交中的按钮保留忙碌光标（.busy）', /button\.busy\{[^}]*cursor:(progress|wait)/.test(css), css.match(/button\.busy\{[^}]*\}/)?.[0]);
    const js = await (await fetch(`http://127.0.0.1:${port}/app.js`)).text();
    check('分页按钮带禁用原因提示', js.includes('disabled title="已经是第一页"') && js.includes('disabled title="已经是最后一页"'), '');
  } finally {
    server.kill();
    await sleep(500);
    try { rmSync(storage, { recursive: true, force: true }); } catch { }
  }
  console.log(failures ? `FAILURES=${failures}` : 'ALL_PASS');
  process.exit(failures ? 1 : 0);
}

main().catch(e => { console.error('测试异常：', e.message); process.exit(1); });
