// Run after mvn package. Requires Playwright and Microsoft Edge.
// Uses an isolated in-memory database; never accesses the project's data directory.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { spawn } = require('node:child_process');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { resolve, join } = require('node:path');
const { randomUUID } = require('node:crypto');
const assert = require('node:assert/strict');

async function main() {
  const storage = mkdtempSync(join(tmpdir(), 'qiheng-auth-'));
  const password = 'Temp-' + randomUUID();
  const newPassword = 'Changed-' + randomUUID();
  const base = 'http://127.0.0.1:18081';
  const server = spawn('java', ['-jar', resolve('target/ContractHub-1.0.0.jar'),
    '--server.port=18081', '--spring.datasource.url=jdbc:h2:mem:authtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
    '--app.worker-enabled=false', '--app.storage=' + storage,
    '--init-admin', '--username=auth_admin', '--display-name=测试管理员', '--password=' + password],
    { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let logs = '', browser;
  server.stdout.on('data', b => { logs += b.toString(); });
  server.stderr.on('data', b => { logs += b.toString(); });
  try {
    for (let i = 0; i < 80; i++) {
      if (server.exitCode !== null) throw Error('Test server failed: ' + logs.slice(-2000));
      if (logs.includes('管理员初始化完成')) break;
      await new Promise(r => setTimeout(r, 250));
    }
    assert(logs.includes('管理员初始化完成'), 'Test server initialization timed out');
    browser = await chromium.launch({ channel: 'msedge', headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    const errors = [], businessRequests = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('request', req => { if (/\/api\/v1\/(contracts|templates|users)/.test(req.url())) businessRequests.push(req.url()); });
    async function visible(selector, expected) {
      await page.locator(selector).waitFor({ state: expected ? 'visible' : 'hidden' });
    }
    async function login(pass) {
      await page.locator('#login-form [name=username]').fill('auth_admin');
      await page.locator('#login-form [name=password]').fill(pass);
      await page.locator('#login-form button').click();
    }
    async function request(path, method = 'GET', body) {
      return page.evaluate(async ({ path, method, body }) => {
        const token = document.cookie.split('; ').find(s => s.startsWith('QH_CSRF='))?.slice(8) || '';
        const r = await fetch('/api/v1' + path, { method, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': token }, body: body ? JSON.stringify(body) : undefined });
        return { status: r.status, body: await r.json() };
      }, { path, method, body });
    }

    await page.goto(base + '/#contracts');
    await visible('#login', true); await visible('header', false); await visible('#logout', false);
    await visible('#workspace', false); await visible('#modal', false);
    await login('wrong-password');
    await page.getByRole('alert').filter({ hasText: '账号或密码错误' }).waitFor();
    await visible('header', false);
    await login(password);
    await visible('#password-change', true); await visible('#login', false); await visible('#workspace', false);
    await visible('header nav', false); await visible('#logout', true);
    assert.equal(businessRequests.length, 0, 'Must not load business data before password change');
    await page.reload(); await visible('#password-change', true);
    const csrfA = await request('/auth/csrf'), csrfB = await request('/auth/csrf');
    assert.equal(csrfA.body.csrf_token, csrfB.body.csrf_token, 'CSRF endpoint must retain the session token');
    assert.equal((await request('/contracts')).status, 403, 'Server must enforce first password change');
    await page.locator('#password-form [name=old_password]').fill(password);
    await page.locator('#password-form [name=new_password]').fill(newPassword);
    await page.locator('#password-form [name=confirm_password]').fill(newPassword + 'different');
    await page.locator('#password-form button').click();
    assert.match(await page.locator('#password-error').innerText(), /不一致/);
    await page.locator('#password-form [name=confirm_password]').fill(newPassword);
    await page.locator('#password-form [name=old_password]').fill('incorrect-current');
    await page.locator('#password-form button').click();
    await page.locator('#password-error').filter({ hasText: '当前密码不正确' }).waitFor();
    await page.locator('#password-form [name=old_password]').fill(password);
    await page.locator('#password-form button').click();
    await visible('#dashboard', true); await visible('#password-change', false); await visible('#login', false);
    assert.equal((await request('/auth/me')).body.must_change_password, false);
    await page.reload(); await visible('#dashboard', true);
    await request('/auth/csrf');
    await page.locator('nav a[href="#templates"]').click();
    await page.locator('[data-action=new-template]').click(); await visible('#modal', true);
    await page.locator('#modal .close').click(); await visible('#modal', false);
    await page.locator('[data-action=new-template]').click(); await page.keyboard.press('Escape'); await visible('#modal', false);
    await page.locator('[data-action=new-template]').click();
    await page.locator('#modal').click({ position: { x: 5, y: 5 } }); await visible('#modal', false);
    const created = await request('/users', 'POST', { username: 'auth_user', display_name: '测试用户', role: 'USER', password });
    assert.equal(created.status, 200, 'Authenticated write after reload must use current CSRF token');
    await page.locator('#logout').click();
    await visible('#login', true); await visible('header', false); await visible('#workspace', false);
    assert.equal((await request('/auth/me')).status, 401, 'Logout must revoke server session');
    await page.reload(); await visible('#login', true);
    await login(newPassword); await visible('#dashboard', true);
    await page.locator('#logout').click(); await visible('#login', true);
    await page.locator('#login-form [name=username]').fill('auth_user');
    await page.locator('#login-form [name=password]').fill(password);
    await page.locator('#login-form button').click(); await visible('#password-change', true);
    await page.locator('#logout').click(); await visible('#login', true);
    await login(newPassword); await visible('#dashboard', true);
    await visible('nav a[href="#users"]', true);
    await context.clearCookies();
    await page.locator('nav a[href="#contracts"]').click();
    await visible('#login', true); await visible('header', false); await visible('#workspace', false);
    assert.deepEqual(errors, [], 'No browser script errors');
    console.log('PASS: anonymous UI, wrong password, first-login gate, password validation/change, CSRF rotation/refresh, dashboard, modal close, logout/relogin, role switching, session expiry.');
  } finally {
    if (browser) await browser.close();
    server.kill();
    await new Promise(r => { if(server.exitCode !== null) r(); else server.once('exit', r); });
    rmSync(storage, { recursive: true, force: true });
  }
}
main().catch(e => { console.error(e); process.exitCode = 1; });
