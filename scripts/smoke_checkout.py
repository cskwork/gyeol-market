#!/usr/bin/env python3
"""Exercise all storefront routes through Vite, plus signed PG callbacks and throttling."""
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import sys
sys.dont_write_bytecode = True
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
MODULE = importlib.util.spec_from_file_location('catalog_auth_smoke', ROOT / 'scripts/smoke_catalog_auth.py')
COMMON = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(COMMON)
TRANSCRIPT = Path(os.environ.get('LUNEPICK_EVIDENCE_DIR', tempfile.gettempdir())) / 'smoke-checkout-2026-10-28.txt'
SECRET = 'lunepick-local-webhook-secret-32-bytes-minimum'


def curl(server, method, path, body=None, access=None, headers=None):
    config = ['silent', 'show-error', f'url = {json.dumps(server + path)}', f'request = {json.dumps(method)}',
              'header = "Accept: application/json"', 'dump-header = "-"', 'write-out = "\\n%{http_code}"']
    if access:
        config.append(f'header = {json.dumps("Authorization: Bearer " + access)}')
    for key, value in (headers or {}).items():
        config.append(f'header = {json.dumps(key + ": " + value)}')
    if body is not None:
        config.extend(['header = "Content-Type: application/json"',
                       f'data = {json.dumps(json.dumps(body, ensure_ascii=False), ensure_ascii=False)}'])
    result = subprocess.run(['curl', '--config', '-'], input='\n'.join(config), text=True,
                            capture_output=True, check=True, timeout=20)
    raw, status = result.stdout.rsplit('\n', 1)
    head, payload = raw.split('\n\n', 1)
    response_headers = {line.split(':', 1)[0].lower(): line.split(':', 1)[1].strip()
                        for line in head.splitlines()[1:] if ':' in line}
    return int(status), json.loads(payload), response_headers


def main():
    runtime = Path(tempfile.mkdtemp(prefix='lunepick-checkout-'))
    cluster = runtime / 'pgdata'
    pg = Path(os.environ.get('PG_BIN', '/opt/homebrew/bin'))
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    db_port, app_port, vite_port, management_port = (COMMON.free_port() for _ in range(4))
    assert len({db_port, app_port, vite_port, management_port}) == 4
    app_url, vite_url = f'http://127.0.0.1:{app_port}', f'http://127.0.0.1:{vite_port}'
    management_url = f'http://127.0.0.1:{management_port}'
    app = vite = None
    access = None
    lines = ['LUNEPICK checkout live smoke, 2026-10-28',
             f'PostgreSQL port={db_port}; Spring Boot port={app_port}; Vite port={vite_port}; Management port={management_port}',
             'Every /api shopper request goes through the non-demo Vite proxy.',
             'curl receives auth request config on stdin; tokens, passwords and HMAC signatures are redacted.',
             'Smoke limits: login IP 50/minute; login email 3/minute; other guards use configured defaults.',
             subprocess.check_output([str(pg / 'initdb'), '--version'], text=True).strip()]

    def check(method, path, expected=200, body=None, headers=None, authenticated=True, server=None, envelope=True):
        server = server or vite_url
        status, payload, response_headers = curl(server, method, path, body, access if authenticated else None, headers)
        lines.append(f'$ curl {method} {server}{path}' + (' Authorization: Bearer <redacted>' if authenticated and access else ''))
        for key, value in (headers or {}).items():
            lines.append(f'header: {key}: ' + ('<redacted>' if key.lower() == 'x-pg-signature' else value))
        if body is not None:
            lines.append('request: ' + json.dumps(COMMON.redacted(body), ensure_ascii=False))
        lines.append(f'HTTP {status} ' + json.dumps(COMMON.redacted(payload), ensure_ascii=False, separators=(',', ':')))
        if 'retry-after' in response_headers:
            lines.append('Retry-After: ' + response_headers['retry-after'])
        assert status == expected, (method, path, status, expected)
        if envelope:
            assert payload.get('success') == (expected < 400), (method, path)
        return payload, response_headers

    def wait_ready(server, path, process):
        until = time.monotonic() + 90
        while time.monotonic() < until:
            if process.poll() is not None:
                raise RuntimeError(f'Process exited {process.returncode}; logs in {runtime}')
            try:
                if curl(server, 'GET', path)[0] == 200:
                    return
            except (subprocess.SubprocessError, ValueError):
                pass
            time.sleep(0.25)
        raise TimeoutError(f'Startup timeout; logs in {runtime}')

    try:
        subprocess.run([str(pg / 'initdb'), '-D', str(cluster), '-A', 'trust', '-U', 'postgres', '--no-locale', '-E', 'UTF8', '--no-sync'],
                       check=True, stdout=subprocess.DEVNULL)
        subprocess.run([str(pg / 'pg_ctl'), '-D', str(cluster), '-l', str(runtime / 'postgres.log'), '-o',
                        f'-p {db_port} -h 127.0.0.1 -k {runtime}', '-w', 'start'], check=True, stdout=subprocess.DEVNULL)
        env = dict(os.environ, LUNEPICK_DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/postgres',
                   LUNEPICK_DB_USER='postgres', LUNEPICK_DB_PASSWORD='', LUNEPICK_PAYMENT_WEBHOOK_SECRET=SECRET,
                   LUNEPICK_MANAGEMENT_PORT=str(management_port))
        with (runtime / 'app.log').open('w') as output:
            app = subprocess.Popen([str(java), '-jar', str(ROOT / 'target/lunepick-0.1.0-SNAPSHOT.jar'),
                                    '--spring.profiles.active=local', '--server.address=127.0.0.1', f'--server.port={app_port}',
                                    '--lunepick.rate-limit.login-ip=50', '--lunepick.rate-limit.login-email=3',
                                    '--lunepick.order.expiry.ttl=5s', '--lunepick.order.expiry.delay=1s'],
                                   cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        wait_ready(app_url, '/actuator/health', app)
        with (runtime / 'vite.log').open('w') as output:
            vite = subprocess.Popen(['npm', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', str(vite_port), '--strictPort'],
                                    cwd=ROOT / 'frontend', env=dict(os.environ, VITE_DEMO='0', VITE_API_PROXY_TARGET=app_url),
                                    stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        wait_ready(vite_url, '/api/products?size=1', vite)
        credentials = {'email': 'shopper-smoke@example.test', 'password': 'smoke1234'}
        check('POST', '/api/auth/signup', 201, {**credentials, 'name': 'Smoke shopper'}, authenticated=False)
        login, _ = check('POST', '/api/auth/login', body=credentials, authenticated=False)
        refresh = login['data']['refreshToken']
        rotated, _ = check('POST', '/api/auth/refresh', body={'refreshToken': refresh}, authenticated=False)
        assert rotated['data']['refreshToken'] != refresh
        access = rotated['data']['accessToken']
        for path in ['/api/categories', '/api/categories/2/products?size=2', '/api/products?sort=LATEST&size=2',
                     '/api/products/rankings?size=2', '/api/products/best-sellers?size=2', '/api/products/3',
                     '/api/products/3/reviews', '/api/search/products?keyword=' + quote('시카'),
                     '/api/search/autocomplete?prefix=' + quote('크림'), '/api/search/popular']:
            check('GET', path)
        check('POST', '/api/cart/merge', body={'sessionId': 'smoke-empty-guest-session'})
        check('POST', '/api/me/wishlist', body={'productId': 3})
        wishlist, _ = check('GET', '/api/me/wishlist')
        assert len(wishlist['data']) == 1
        check('DELETE', '/api/me/wishlist/3')
        address, _ = check('POST', '/api/me/addresses', 201,
                           {'recipientName': ' Smoke shopper ', 'phone': '01000000000', 'zipcode': '04500',
                            'addressMain': 'Synthetic address', 'isDefault': True})
        assert address['data']['phone'] == '010-0000-0000'
        check('GET', '/api/me/addresses')
        coupons, _ = check('GET', '/api/me/coupons')
        coupon = next(item for item in coupons['data'] if item['couponId'] == 2)
        item, _ = check('POST', '/api/cart/items', 201, {'productOptionId': 4, 'quantity': 2})
        check('PATCH', f"/api/cart/items/{item['data']['cartItemId']}", body={'quantity': 2})
        extra, _ = check('POST', '/api/cart/items', 201, {'productOptionId': 1, 'quantity': 1})
        check('DELETE', f"/api/cart/items/{extra['data']['cartItemId']}")
        cart, _ = check('GET', '/api/cart')
        assert cart['data']['totalAmount'] == 50400
        body = {'items': [{'productOptionId': 4, 'quantity': 2}], 'couponId': coupon['id'],
                'usePointAmount': 1000, 'deliveryAddressId': address['data']['id']}
        key = {'Idempotency-Key': 'checkout-smoke-order'}
        created, _ = check('POST', '/api/orders', body=body, headers=key)
        replay, _ = check('POST', '/api/orders', body=body, headers=key)
        assert replay == created and created['data']['amount'] == 44360
        conflict, _ = check('POST', '/api/orders', 409, {**body, 'usePointAmount': 0}, key)
        assert conflict['error']['code'] == 'IDEMPOTENCY_CONFLICT'
        payment = {'orderNo': created['data']['orderNo'], 'paymentKey': str(created['data']['paymentKey']), 'amount': 44360}
        mismatch, _ = check('POST', '/api/payments/confirm', 422, {**payment, 'amount': 1})
        assert mismatch['error']['code'] == 'PAYMENT_AMOUNT_MISMATCH'
        paid, _ = check('POST', '/api/payments/confirm', body=payment)
        duplicate, _ = check('POST', '/api/payments/confirm', body=payment)
        assert paid == duplicate and paid['data']['status'] == 'PAID'
        history, _ = check('GET', '/api/orders?status=PAID&page=0&size=10')
        assert len(history['data']) == 1
        detail, _ = check('GET', '/api/orders/' + payment['orderNo'])
        assert detail['data']['discountAmount'] == 5040 and detail['data']['finalPaymentAmount'] == 44360
        summary, _ = check('GET', '/api/me/summary')
        assert summary['data'] == {'pointBalance': 4000, 'usableCouponCount': 2, 'totalOrderCount': 1, 'gradeName': 'BRONZE'}
        cleared, _ = check('GET', '/api/cart')
        assert cleared['data']['items'] == []
        event = {**payment, 'eventId': 'checkout-smoke-pg-event', 'status': 'CONFIRMED'}
        def webhook(event, timestamp=None, expected=200):
            timestamp = str(int(time.time())) if timestamp is None else str(timestamp)
            raw = json.dumps(event, ensure_ascii=False).encode('utf8')
            signature = hmac.new(SECRET.encode('utf8'), timestamp.encode('ascii') + b'.' + raw, hashlib.sha256).hexdigest()
            return check('POST', '/api/payments/webhook', expected, body=event,
                         headers={'X-PG-Signature': signature, 'X-PG-Timestamp': timestamp}, authenticated=False)[0]

        callback = webhook(event)
        again = webhook(event)
        assert callback['data']['processed'] is True and again['data']['processed'] is False
        rejected = webhook({**event, 'eventId': 'review-stale-event'}, int(time.time()) - 301, 401)
        assert rejected['error']['code'] == 'INVALID_WEBHOOK_SIGNATURE'
        review = webhook({**event, 'eventId': 'review-out-of-order-paid', 'status': 'FAILED'})
        assert review['data']['processed'] is True

        def sql(statement):
            return subprocess.check_output([str(pg / 'psql'), '-h', '127.0.0.1', '-p', str(db_port),
                    '-U', 'postgres', '-d', 'postgres', '-At', '-c', statement], text=True).strip()

        assert sql("SELECT status FROM payment_webhook_events WHERE event_id='review-out-of-order-paid'") == 'NEEDS_REVIEW'
        lines.append('Out-of-order FAILED on PAID: HTTP200; persisted NEEDS_REVIEW; paid order unchanged.')
        mismatch_event = {**event, 'eventId': 'review-webhook-amount-mismatch', 'amount': 1}
        assert webhook(mismatch_event)['data']['processed'] is True
        assert sql("SELECT status FROM payment_webhook_events WHERE event_id='review-webhook-amount-mismatch'") == 'NEEDS_REVIEW'
        assert sql("SELECT review_reason FROM payment_webhook_events WHERE event_id='review-webhook-amount-mismatch'") == 'PAYMENT_AMOUNT_MISMATCH'
        key_event = {**event, 'eventId': 'review-webhook-key-mismatch', 'paymentKey': 'wrong-provider-key'}
        assert webhook(key_event)['data']['processed'] is True
        assert sql("SELECT status FROM payment_webhook_events WHERE event_id='review-webhook-key-mismatch'") == 'NEEDS_REVIEW'
        metric, _ = check('GET', '/actuator/metrics/lunepick.payment.webhook.needs_review',
                          server=management_url, authenticated=False, envelope=False)
        assert metric['measurements'][0]['value'] >= 3
        prometheus = subprocess.check_output(['curl', '--fail', '--silent', management_url + '/actuator/prometheus'], text=True)
        assert 'lunepick_payment_webhook_needs_review_total' in prometheus
        lines.append('PASS: separate management metrics/prometheus200; webhook mismatch200 NEEDS_REVIEW counter>=3.')
        check('GET', '/actuator/metrics', 403, server=app_url)
        deep = subprocess.check_output(['curl', '--fail', '--silent', app_url + '/app/products/3'], text=True)
        assert '<div id="root">' in deep
        lines.append('$ curl GET ' + app_url + '/app/products/3 -> HTTP200 React entry (deep-link refresh)')

        # One finite-stock checkout is deliberately abandoned. The real scheduler must release it.
        sql('UPDATE product_option SET available_quantity=1 WHERE id=1')
        coupon = next(item for item in coupons['data'] if item['couponId'] == 1)
        abandoned_body = {'items': [{'productOptionId': 1, 'quantity': 1}], 'couponId': coupon['id'],
                          'usePointAmount': 1000, 'deliveryAddressId': address['data']['id']}
        abandoned, _ = check('POST', '/api/orders', body=abandoned_body,
                             headers={'Idempotency-Key': 'review-abandoned-order'})
        available, _ = check('GET', '/api/me/summary')
        assert available['data']['pointBalance'] == 3000
        assert sql('SELECT available_quantity FROM product_option WHERE id=1') == '0'
        until = time.monotonic() + 20
        while sql("SELECT status FROM orders WHERE order_no='" + abandoned['data']['orderNo'] + "'") != 'CANCELLED':
            assert time.monotonic() < until, 'Abandoned checkout did not expire'
            time.sleep(0.2)
        restored, _ = check('GET', '/api/me/summary')
        assert restored['data']['pointBalance'] == 4000
        assert sql('SELECT available_quantity FROM product_option WHERE id=1') == '1'
        invalid_state, _ = check('POST', '/api/payments/confirm', 409,
                    body={'orderNo': abandoned['data']['orderNo'],
                          'paymentKey': str(abandoned['data']['paymentKey']),
                          'amount': abandoned['data']['amount']})
        assert invalid_state['error']['code'] == 'INVALID_ORDER_STATE'
        lines.append('PASS: confirming expired order ->409 INVALID_ORDER_STATE.')
        available_coupons, _ = check('GET', '/api/me/coupons')
        assert next(c for c in available_coupons['data'] if c['id'] == coupon['id'])['status'] == 'ISSUED'
        expired_event = {'eventId': 'review-confirmed-after-expiry', 'orderNo': abandoned['data']['orderNo'],
                         'paymentKey': str(abandoned['data']['paymentKey']), 'amount': abandoned['data']['amount'],
                         'status': 'CONFIRMED'}
        assert webhook(expired_event)['data']['processed'] is True
        assert sql("SELECT status FROM payment_webhook_events WHERE event_id='review-confirmed-after-expiry'") == 'NEEDS_REVIEW'
        reused, _ = check('POST', '/api/orders', body=abandoned_body,
                         headers={'Idempotency-Key': 'review-after-expiry'})
        check('POST', '/api/payments/confirm', body={'orderNo': reused['data']['orderNo'],
              'paymentKey': str(reused['data']['paymentKey']), 'amount': reused['data']['amount']})
        lines.append('PASS expiry: available points4000->3000->4000; finite stock1->0->1; coupon reused successfully.')
        until = time.monotonic() + 15
        while sql("SELECT count(*) FROM search_index_jobs WHERE status='COMPLETED'") == '0':
            assert time.monotonic() < until, 'Scheduled search worker did not run'
            time.sleep(0.2)
        for _ in range(3):
            check('POST', '/api/auth/login', 401, {'email': 'rate-smoke@example.test', 'password': 'wrong1234'}, authenticated=False)
        limited, headers = check('POST', '/api/auth/login', 429,
                                  {'email': 'rate-smoke@example.test', 'password': 'wrong1234'}, authenticated=False)
        assert limited['error']['code'] == 'RATE_LIMITED' and int(headers['retry-after']) > 0
        sql = "SELECT 'orders', count(*) FROM orders UNION ALL SELECT 'payments', count(*) FROM payment_confirmations UNION ALL SELECT 'webhook_events', count(*) FROM payment_webhook_events; SELECT status, count(*) FROM event_publication GROUP BY status; SELECT status, count(*) FROM search_index_jobs GROUP BY status;"
        counts = subprocess.check_output([str(pg / 'psql'), '-h', '127.0.0.1', '-p', str(db_port), '-U', 'postgres', '-d', 'postgres', '-At', '-c', sql], text=True)
        lines.extend(['$ psql: committed order/payment/webhook/outbox/search-job counts', counts.strip()])
        check('POST', '/api/auth/logout')
        lines.append('PASS: 30/30 storefront routes, totals, replay, 422 mismatch, expiry/release/reuse, 429 Retry-After, timestamp/signature/dedupe/NEEDS_REVIEW, deep-link refresh, scheduled indexing, metrics denied.')
    finally:
        COMMON.stop_group(vite)
        COMMON.stop_group(app)
        if (cluster / 'postmaster.pid').exists():
            subprocess.run([str(pg / 'pg_ctl'), '-D', str(cluster), '-m', 'fast', '-w', 'stop'], check=True, stdout=subprocess.DEVNULL)
        for port in (db_port, app_port, vite_port, management_port):
            until = time.monotonic() + 5
            while True:
                with socket.socket() as probe:
                    probe.settimeout(0.2)
                    if probe.connect_ex(('127.0.0.1', port)) != 0:
                        break
                if time.monotonic() >= until:
                    raise RuntimeError(f'Cleanup failed: started port {port} is still open')
                time.sleep(0.1)
        lines.append('Cleanup: started Vite/application groups and PostgreSQL stopped; all four ports closed.')
        TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
        TRANSCRIPT.write_text('\n'.join(lines) + '\n')
    print(f'PASS: {TRANSCRIPT}; runtime logs: {runtime}')


if __name__ == '__main__':
    main()
