"""Isolated Linux/WSL browser QA. Creates only a new disposable PostgreSQL database.
Requires Java 21, Docker, and Playwright/Chromium installed under .tools (see docs/TESTING.md).
No real Google credentials or existing application database are used.
"""
import os, sys, subprocess, time, urllib.request, json, secrets, socket
from local_smtp_sink import LocalSmtpSink
from pathlib import Path
p = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(p / '.tools/browser-lib'))
os.environ['PLAYWRIGHT_BROWSERS_PATH'] = str(p / '.tools/pw-browsers')
from playwright.sync_api import sync_playwright, expect

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]
appport = port()
name = 'nilebank-qa-' + secrets.token_hex(5)
password = secrets.token_urlsafe(24)
env = os.environ.copy()
env.update(POSTGRES_PASSWORD=password)
sink = LocalSmtpSink()
app = None
created = False
log = open(p / '.tools/browser-runtime.log', 'w')
try:
    subprocess.run(['docker', 'run', '--rm', '-d', '--name', name, '--label', 'nilebank.qa=isolated', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_USER=qa', '-e', 'POSTGRES_DB=qa', '-p', '127.0.0.1:0:5432', 'postgres:17-alpine'], env=env, check=True, stdout=subprocess.DEVNULL)
    created = True
    dbport = int(subprocess.check_output(['docker', 'port', name, '5432/tcp'], text=True).strip().rsplit(':', 1)[1])
    for _ in range(60):
        if subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'qa', '-d', 'qa'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            break
        time.sleep(1)
    env.update(DB_URL=f'jdbc:postgresql://localhost:{dbport}/qa', DB_USER='qa', DB_PASSWORD=password, SERVER_PORT=str(appport), SERVER_ADDRESS='127.0.0.1', SPRING_PROFILES_ACTIVE='default', GOOGLE_LOGIN_ENABLED='false', COOKIE_SECURE='false', FINANCE_SCHEDULER_ENABLED='false')
    env.update(RECOVERY_ENABLED='true', RECOVERY_BASE_URL=f'http://127.0.0.1:{appport}', RECOVERY_FROM='qa-recovery@example.test', RECOVERY_SMTP_HOST='127.0.0.1', RECOVERY_SMTP_PORT=str(sink.port), RECOVERY_SMTP_STARTTLS='false')
    import base64
    env.update(MFA_ENABLED='true', MFA_ACTIVE_KEY_ID='qa', MFA_KEYS_JSON=json.dumps({'qa': base64.b64encode(secrets.token_bytes(32)).decode('ascii')}))
    app = subprocess.Popen(['java', '-jar', str(p / 'backend/target/nilebank-0.1.0.jar')], env=env, stdout=log, stderr=subprocess.STDOUT)
    base = f'http://127.0.0.1:{appport}'
    for _ in range(90):
        if app.poll() is not None:
            raise RuntimeError('Application stopped; inspect ignored runtime log')
        try:
            if urllib.request.urlopen(base + '/api/v1/health', timeout=1).status == 200:
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Application readiness timeout')
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        ctx = browser.new_context(viewport={'width': 1440, 'height': 1000})
        page = ctx.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('dialog', lambda d: d.accept())
        page.goto(base)
        expect(page.locator('#auth-view')).to_be_visible()
        email = 'browser-' + secrets.token_hex(5) + '@example.test'
        pwtext = 'Fictional-QA-password-2026!'
        page.locator('#register-tab').click()
        page.locator('#full-name').fill('Browser Demo')
        page.locator('#email').fill(email)
        page.locator('#password').fill(pwtext)
        page.locator('#auth-submit').click()
        expect(page.locator('#auth-submit')).to_contain_text('Sign in')
        page.locator('#password').fill(pwtext)
        page.locator('#auth-submit').click()
        expect(page.locator('#bank-view')).to_be_visible()
        page.locator('#create-account').click()
        expect(page.locator('#account-dashboard')).to_be_visible()
        print('PASS browser registration, login, session, account creation', flush=True)
        for mode, amount in [('deposit', '1000'), ('withdraw', '25')]:
            page.locator('[data-action="' + mode + '"]').first.click()
            page.locator('#money-amount').fill(amount)
            page.locator('#money-submit').click()
            expect(page.locator('#money-dialog')).not_to_be_visible()
        expect(page.locator('#balance')).to_contain_text('975')
        print('PASS browser deposit and withdrawal', flush=True)

        def nav(view):
            page.locator('[data-view="' + view + '"]').first.click()
            expect(page.locator('#' + view + '-view')).to_be_visible()
        sys.path.insert(0, str(p / 'scripts'))
        import smoke
        smoke.BASE_URL = base
        recipient = smoke.Browser().account()
        nav('transfer')
        page.locator('#recipient').fill(recipient['accountNumber'])
        page.locator('#transfer-amount').fill('50')
        page.locator('#transfer-form button[type=submit]').click()
        page.locator('#confirm-transfer').click()
        expect(page.locator('#confirm-dialog')).not_to_be_visible()
        print('PASS browser confirmed internal transfer to second account', flush=True)
        nav('savings')
        page.locator('#savings-name').fill('Emergency demo')
        page.locator('#savings-target').fill('500')
        page.locator('#savings-account-save').click()
        expect(page.locator('#savings-accounts')).to_contain_text('Emergency demo')
        page.locator('#savings-move-account').select_option(label='Emergency demo')
        page.locator('#savings-amount').fill('100')
        page.locator('#savings-move-form button[type=submit]').click()
        page.locator('#savings-confirm').click()
        expect(page.locator('#savings-main-balance')).to_contain_text('825')
        page.locator('#savings-direction').select_option('OUT')
        page.locator('#savings-amount').fill('25')
        page.locator('#savings-move-form button[type=submit]').click()
        page.locator('#savings-confirm').click()
        expect(page.locator('#savings-main-balance')).to_contain_text('850')
        print('PASS browser savings creation, review, IN/OUT, balance refresh', flush=True)
        nav('planning')
        page.locator('#category-label').fill('Demo groceries')
        page.locator('#category-save').click()
        expect(page.locator('#planning-categories')).to_contain_text('Demo groceries')
        from datetime import datetime
        from zoneinfo import ZoneInfo
        page.locator('#rule-category').select_option(label='Demo groceries')
        page.locator('#rule-amount').fill('3.25')
        page.locator('#rule-description').fill('Recurring demo')
        page.locator('#rule-start').fill(datetime.now(ZoneInfo('Asia/Bangkok')).date().isoformat())
        page.locator('#rule-save').click()
        expect(page.locator('#planning-rules')).to_contain_text('Recurring demo')
        nav('finance')
        expect(page.locator('#finance-entries')).to_contain_text('Recurring demo')
        page.locator('#budget-category').select_option(label='Demo groceries')
        page.locator('#budget-amount').fill('100')
        page.locator('#finance-budget-form button').click()
        expect(page.locator('#finance-categories')).to_contain_text('Demo groceries')
        page.locator('#entry-amount').fill('12.50')
        page.locator('#entry-description').fill('Fictional lunch')
        page.locator('#entry-submit').click()
        expect(page.locator('#finance-entries')).to_contain_text('Fictional lunch')
        print('PASS browser category creation and manual expense tracking', flush=True)
        nav('exchange')
        expect(page.locator('#fx-wallets')).to_contain_text('JPY')
        page.locator('#fx-from').select_option('THB')
        page.locator('#fx-to').select_option('USD')
        page.locator('#fx-amount').fill('35')
        page.locator('#fx-quote-button').click()
        try:
            expect(page.locator('#fx-review')).to_be_visible(timeout=30000)
            page.locator('#fx-confirm').click()
            expect(page.locator('#fx-review')).not_to_be_visible(timeout=20000)
            print('PASS browser external reference quote and exchange execution', flush=True)
        except Exception:
            print('FX external walkthrough unavailable: ' + page.locator('#fx-error').inner_text(), flush=True)
            raise
        nav('partners')
        page.locator('#partner-bank').select_option(index=1)
        page.locator('#partner-consent').check()
        page.locator('#partner-link-form button').click()
        expect(page.locator('#partner-connections button')).to_have_count(1)
        page.locator('#partner-connection').select_option(index=1)
        page.locator('#partner-amount').fill('10')
        page.locator('#partner-move-form button').click()
        page.locator('#partner-confirm').click()
        expect(page.locator('#partner-review')).not_to_be_visible()
        print('PASS browser fictional partner linking and confirmed movement', flush=True)
        for view in ['partners', 'history', 'profile', 'security', 'overview', 'transfer']:
            nav(view)
        page.set_viewport_size({'width': 390, 'height': 844})
        for view in ['overview', 'finance', 'planning', 'savings', 'exchange', 'history', 'security']:
            nav(view)
            assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile overflow: ' + view
        assert not errors, errors
        old_context = browser.new_context()
        old_page = old_context.new_page()
        old_page.goto(base)
        old_page.locator('#email').fill(email)
        old_page.locator('#password').fill(pwtext)
        old_page.locator('#auth-submit').click()
        expect(old_page.locator('#bank-view')).to_be_visible()
        page.locator('#logout').click()
        expect(page.locator('#auth-view')).to_be_visible()
        print('PASS browser all navigation, 390px mobile widths, logout; no page errors', flush=True)
        page.locator('#forgot-password').click()
        page.locator('#recovery-email').fill(email)
        page.locator('#recovery-submit').click()
        for _ in range(150):
            if any((email in message for message in sink.messages)):
                break
            page.wait_for_timeout(100)
        message = next((message for message in sink.messages if email in message))
        import re
        token = re.search('#reset=([A-Za-z0-9_-]{43})', message).group(1)
        page.goto(base + '/#reset=' + token)
        expect(page.locator('#reset-form')).to_be_visible()
        assert '#reset=' not in page.url
        replacement = 'Fictional-browser-reset-password-2026!'
        page.locator('#reset-password').fill(replacement)
        page.locator('#reset-confirm').fill(replacement)
        page.locator('#reset-submit').click()
        expect(page.locator('#reset-form')).not_to_be_visible()
        old_page.reload()
        expect(old_page.locator('#auth-view')).to_be_visible()
        expect(old_page.locator('#bank-view')).not_to_be_visible()
        page.locator('#email').fill(email)
        page.locator('#password').fill(replacement)
        page.locator('#auth-submit').click()
        expect(page.locator('#bank-view')).to_be_visible()
        page.locator('#logout').click()
        expect(page.locator('#auth-view')).to_be_visible()
        assert not errors, errors
        old_context.close()
        print('PASS browser SMTP recovery, fragment removal, new-password login and old-session revocation', flush=True)
        page.locator('#email').fill(email)
        page.locator('#password').fill(replacement)
        page.locator('#auth-submit').click()
        expect(page.locator('#bank-view')).to_be_visible()
        nav('security')
        expect(page.locator('#mfa-enroll')).to_be_enabled()
        page.locator('#mfa-current-password').fill(replacement)
        page.locator('#mfa-enroll').click()
        expect(page.locator('#mfa-setup')).to_be_visible()
        seed = page.locator('#mfa-secret').inner_text()
        assert len(seed) == 32
        import hmac, hashlib, struct

        def otp(counter):
            digest = hmac.new(base64.b32decode(seed), struct.pack('>Q', counter), hashlib.sha1).digest()
            offset = digest[-1] & 15
            number = int.from_bytes(digest[offset:offset + 4], 'big') & 2147483647
            return f'{number % 1000000:06d}'
        counter = int(time.time()) // 30
        enrollment_code = otp(counter)
        page.locator('#mfa-current-password').fill(replacement)
        page.locator('#mfa-settings-code').fill(enrollment_code)
        page.locator('#mfa-confirm').click()
        expect(page.locator('#mfa-codes-dialog')).to_be_visible()
        original_codes = page.locator('#mfa-codes code').all_text_contents()
        assert len(original_codes) == 8
        assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
        page.locator('#mfa-codes-close').click()
        expect(page.locator('#mfa-codes code')).to_have_count(0)
        page.locator('#email').fill(email)
        page.locator('#password').fill(replacement)
        page.locator('#auth-submit').click()
        expect(page.locator('#mfa-challenge-panel')).to_be_visible()
        assert page.request.get(base + '/api/v1/accounts/me').status == 403
        page.locator('#mfa-login-code').fill(enrollment_code)
        page.locator('#mfa-login-submit').click()
        expect(page.locator('#mfa-challenge-error')).to_be_visible()
        page.locator('#mfa-login-code').fill(otp(int(time.time()) // 30 + 1))
        page.locator('#mfa-login-submit').click()
        expect(page.locator('#bank-view')).to_be_visible()
        assert page.locator('#mfa-secret').inner_text() == ''
        assert page.locator('#mfa-setup-details').inner_text() == ''
        nav('security')
        page.locator('#mfa-current-password').fill(replacement)
        page.locator('#mfa-regenerate').click()
        expect(page.locator('#mfa-codes-dialog')).to_be_visible()
        new_codes = page.locator('#mfa-codes code').all_text_contents()
        assert len(new_codes) == 8 and set(new_codes).isdisjoint(original_codes)
        page.locator('#mfa-codes-close').click()
        page.locator('#email').fill(email)
        page.locator('#password').fill(replacement)
        page.locator('#auth-submit').click()
        expect(page.locator('#mfa-challenge-panel')).to_be_visible()
        page.locator('#mfa-login-code').fill(original_codes[1])
        page.locator('#mfa-login-submit').click()
        expect(page.locator('#mfa-challenge-error')).to_be_visible()
        page.locator('#mfa-login-code').fill(new_codes[0])
        page.locator('#mfa-login-submit').click()
        expect(page.locator('#bank-view')).to_be_visible()
        nav('security')
        page.locator('#mfa-current-password').fill(replacement)
        page.locator('#mfa-disable').click()
        expect(page.locator('#auth-view')).to_be_visible()
        page.locator('#email').fill(email)
        page.locator('#password').fill(replacement)
        page.locator('#auth-submit').click()
        expect(page.locator('#bank-view')).to_be_visible()
        expect(page.locator('#mfa-challenge-panel')).not_to_be_visible()
        page.locator('#logout').click()
        expect(page.locator('#auth-form')).to_be_visible()
        assert not errors, errors
        print('PASS browser MFA enrollment, OTP replay rejection, pending API denial, TOTP login, code replacement/recovery login and secure disable', flush=True)
        browser.close()
finally:
    if app is not None:
        app.terminate()
        try:
            app.wait(timeout=20)
        except subprocess.TimeoutExpired:
            app.kill()
            app.wait()
    sink.close()
    log.close()
    if created:
        subprocess.run(['docker', 'stop', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
