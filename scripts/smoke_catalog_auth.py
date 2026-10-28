#!/usr/bin/env python3
"""Run the catalog/auth acceptance smoke with disposable local processes, without containers."""
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
TRANSCRIPT = Path(os.environ.get('LUNEPICK_EVIDENCE_DIR', tempfile.gettempdir())) / 'smoke_catalog_auth-2026-10-28.txt'
JAR = ROOT / 'target/lunepick-0.1.0-SNAPSHOT.jar'


def free_port():
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0))
        return server.getsockname()[1]


def redacted(value):
    if isinstance(value, dict):
        return {key: '<redacted>' if key.lower() in ('accesstoken', 'refreshtoken', 'password') else redacted(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redacted(item) for item in value]
    return value


def curl(base, method, path, body=None, access=None):
    # Keep credentials out of process arguments, temporary files, and the transcript.
    config = ['silent', 'show-error', f'url = {json.dumps(base + path)}', f'request = {json.dumps(method)}',
              'header = "Accept: application/json"', 'write-out = "\\n%{http_code}"']
    if access:
        config.append(f'header = {json.dumps("Authorization: Bearer " + access)}')
    if body is not None:
        config.extend(['header = "Content-Type: application/json"',
                       f'data = {json.dumps(json.dumps(body, ensure_ascii=False), ensure_ascii=False)}'])
    response = subprocess.run(['curl', '--config', '-'], input='\n'.join(config), text=True,
                              capture_output=True, check=True, timeout=15)
    payload, status = response.stdout.rsplit('\n', 1)
    return int(status), json.loads(payload)


def stop_group(process):
    if process is None:
        return
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=25)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
    # npm may exit before its Vite child; signal only the process group we created.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass


def main():
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    pg_bin = Path(os.environ['PG_BIN']) if os.environ.get('PG_BIN') else Path(shutil.which('initdb')).parent
    runtime = Path(tempfile.mkdtemp(prefix='lunepick-smoke-'))
    cluster = runtime / 'pgdata'
    db_port, app_port, vite_port = free_port(), free_port(), free_port()
    assert len({db_port, app_port, vite_port}) == 3
    base = f'http://127.0.0.1:{app_port}'
    vite_base = f'http://127.0.0.1:{vite_port}'
    app = vite = None
    lines = [f'LUNEPICK catalog/auth smoke, 2026-10-28',
             f'PostgreSQL port={db_port}; Spring Boot port={app_port}; Vite port={vite_port}',
             'Auth request config is supplied to curl on stdin. Tokens/passwords are redacted.',
             subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True).strip(),
             subprocess.check_output([str(pg_bin / 'initdb'), '--version'], text=True).strip()]

    def check(method, path, expected=200, body=None, access=None, server=base):
        status, payload = curl(server, method, path, body, access)
        lines.append(f'$ curl {method} {server}{path}' + (' Authorization: Bearer <redacted>' if access else ''))
        if body is not None:
            lines.append('request: ' + json.dumps(redacted(body), ensure_ascii=False))
        lines.append(f'HTTP {status} ' + json.dumps(redacted(payload), ensure_ascii=False, separators=(',', ':')))
        assert status == expected, (method, path, status, expected)
        assert payload.get('success') == (expected < 400), (method, path, payload)
        return payload

    def wait_ready(url, process):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f'Started process exited: {process.returncode}; logs in {runtime}')
            try:
                status, _ = curl(url, 'GET', '/actuator/health' if url == base else '/api/products?size=1')
                if status == 200:
                    return
            except (subprocess.SubprocessError, ValueError):
                pass
            time.sleep(0.25)
        raise TimeoutError(f'Startup timed out; logs in {runtime}')

    try:
        subprocess.run([str(pg_bin / 'initdb'), '-D', str(cluster), '-A', 'trust', '-U', 'postgres',
                        '--no-locale', '-E', 'UTF8', '--no-sync'], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([str(pg_bin / 'pg_ctl'), '-D', str(cluster), '-l', str(runtime / 'postgres.log'),
                        '-o', f'-p {db_port} -h 127.0.0.1 -k {runtime}', '-w', 'start'],
                       check=True, stdout=subprocess.DEVNULL)
        env = dict(os.environ, LUNEPICK_DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/postgres',
                   LUNEPICK_DB_USER='postgres', LUNEPICK_DB_PASSWORD='')
        with (runtime / 'app.log').open('w') as log:
            app = subprocess.Popen([str(java), '-jar', str(JAR), '--spring.profiles.active=local',
                                    f'--server.port={app_port}'], cwd=ROOT, env=env,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        wait_ready(base, app)
        for path in ['/api/categories', '/api/products?size=2', '/api/categories/2/products?size=2',
                     '/api/products/rankings?size=2', '/api/products/best-sellers?size=2',
                     '/api/products/1', '/api/products/1/reviews',
                     '/api/search/products?keyword=' + quote('밍 시카'),
                     '/api/search/autocomplete?prefix=' + quote('선'), '/api/search/popular']:
            payload = check('GET', path)
            if path.startswith('/api/search/products'):
                assert [product['productId'] for product in payload['data']] == [3]
        check('GET', '/api/products/999999', 404)
        check('POST', '/api/auth/logout', 401)
        signup = {'email': 'smoke@example.test', 'password': 'smoke1234', 'name': 'Smoke fixture'}
        check('POST', '/api/auth/signup', 201, signup)
        check('POST', '/api/auth/signup', 409, signup)
        credentials = {key: signup[key] for key in ('email', 'password')}
        first = check('POST', '/api/auth/login', body=credentials)['data']
        rotated = check('POST', '/api/auth/refresh', body={'refreshToken': first['refreshToken']})['data']
        assert rotated['refreshToken'] != first['refreshToken']
        assert check('POST', '/api/auth/refresh', 401, {'refreshToken': first['refreshToken']})['error']['code'] == 'INVALID_TOKEN'
        assert check('POST', '/api/auth/refresh', 401, {'refreshToken': rotated['refreshToken']})['error']['code'] == 'INVALID_TOKEN'
        check('POST', '/api/auth/logout', 401, access=rotated['accessToken'])
        second = check('POST', '/api/auth/login', body=credentials)['data']
        check('POST', '/api/auth/logout', access=second['accessToken'])
        check('POST', '/api/auth/refresh', 401, {'refreshToken': second['refreshToken']})
        env = dict(os.environ, VITE_DEMO='0', VITE_API_PROXY_TARGET=base)
        with (runtime / 'vite.log').open('w') as log:
            vite = subprocess.Popen(['npm', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', str(vite_port), '--strictPort'],
                                    cwd=ROOT / 'frontend', env=env, stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
        wait_ready(vite_base, vite)
        proxied = check('GET', '/api/products?sort=LATEST&size=1', server=vite_base)
        assert proxied['meta']['total'] == 13 and proxied['data'][0]['productId'] == 13
        image_path = '/images/products/saebomdam-kids-suncream-spf50-thumb.png'
        image_status = subprocess.check_output(['curl', '-sS', '-o', os.devnull, '-w', '%{http_code}', vite_base + image_path], text=True)
        assert image_status == '200'
        lines.extend([f'$ curl GET {vite_base}{image_path} -> HTTP {image_status}',
                      'PASS: 14 API routes, auth replay/family revocation, Vite /api and /images proxy'])
    finally:
        stop_group(vite)
        stop_group(app)
        if (cluster / 'postmaster.pid').exists():
            subprocess.run([str(pg_bin / 'pg_ctl'), '-D', str(cluster), '-m', 'fast', '-w', 'stop'],
                           check=True, stdout=subprocess.DEVNULL)
        for port in (db_port, app_port, vite_port):
            deadline = time.monotonic() + 5
            while True:
                with socket.socket() as probe:
                    probe.settimeout(0.2)
                    closed = probe.connect_ex(('127.0.0.1', port)) != 0
                if closed:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError(f'Cleanup failed: started port {port} still accepts connections')
                time.sleep(0.1)
        lines.append('Cleanup: started Vite and application process groups stopped; PostgreSQL stopped; all three ports closed.')
        TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
        TRANSCRIPT.write_text('\n'.join(lines) + '\n')
    print(f'PASS: {TRANSCRIPT}; disposable runtime logs: {runtime}')


if __name__ == '__main__':
    main()
