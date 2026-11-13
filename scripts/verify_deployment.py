"""Read-only readiness checks for the full Compose deployment (Python standard library)."""
import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-project', default='smarthotel-cloud')
    parser.add_argument('--url', default='http://127.0.0.1:8088')
    args = parser.parse_args()
    address = urllib.parse.urlsplit(args.url)
    if address.scheme not in ('http', 'https') or not address.hostname or address.username or address.password:
        parser.error('Supply an HTTP(S) deployment URL without credentials.')
    root = Path(__file__).resolve().parent.parent
    raw = subprocess.check_output(['docker', 'compose', '-p', args.compose_project, 'ps', '--all', '--format', 'json'], cwd=root, text=True, encoding='utf-8')
    rows = json.loads(raw) if raw.lstrip().startswith('[') else [json.loads(line) for line in raw.splitlines() if line.strip()]
    services = {row['Service']: row for row in rows}
    expected = {'mysql', 'redis', 'user', 'hotel', 'inventory', 'business', 'gateway', 'frontend'}
    checks = []

    def check(label, condition):
        if not condition:
            raise RuntimeError(label)
        checks.append(label)
        print('PASS', label)

    check('eight required services exist', expected <= services.keys())
    for name in sorted(expected):
        check(name + ' running and healthy', services[name].get('State') == 'running' and services[name].get('Health') == 'healthy')

    def get(path):
        try:
            with urllib.request.urlopen(args.url.rstrip('/') + path, timeout=15) as response:
                return response.status, response.read().decode('utf-8')
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode('utf-8')

    for path in ('/', '/home', '/admin'):
        status, body = get(path)
        check('SPA route ' + path, status == 200 and '<div id="app">' in body)
    status, body = get('/api/v1/room-types')
    check('room catalog through frontend and gateway', status == 200 and isinstance(json.loads(body), dict) and json.loads(body).get('code') == 200)
    for path in ('/internal/v1/sessions/introspect', '/actuator/health'):
        status, _ = get(path)
        check('private path blocked: ' + path, status == 404)
    status, _ = get('/api/v1/admin/payments')
    check('administrator API rejects anonymous request', status == 401)
    report = {'timestamp': dt.datetime.now(dt.timezone.utc).isoformat(), 'project': args.compose_project, 'checks': checks, 'passed': len(checks), 'readOnly': True}
    output = root / '.run' / 'deployment-readiness.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'{len(checks)} checks passed. Report: {output}')


if __name__ == '__main__':
    main()
