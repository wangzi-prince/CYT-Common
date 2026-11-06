"""Plan and import registered platform credentials without printing secret payloads.

Cloud reads use the existing gcloud session. Imports target only an explicitly
configured loopback MySQL cyt_common database and preserve existing values unless
--overwrite is explicit. A production partition requires an explicitly targeted plan.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PLAN = ROOT / 'target' / 'gcloud-credential-plan.json'
ALIASES = {
    'SHOPIFY_STORE': ['SHOPIFY_SHOP_DOMAIN'],
    'LINGXING_DEFAULT_APP_ID': ['LINGXING_APP_ID'],
    'LINGXING_DEFAULT_APP_SECRET': ['LINGXING_APP_SECRET'],
    'LINGXING_PROXY_BASE': ['erp_proxy_base'],
    'MABANG_APP_KEY': ['mabang-developer-id'],
    'MABANG_PROXY_BASE': ['erp_proxy_base'],
    'WINIT_APP_KEY': ['CYT_DTC_WINIT_APP_KEY'],
    'WINIT_TOKEN': ['CYT_DTC_WINIT_TOKEN'],
    'WINIT_CLIENT_ID': ['CYT_DTC_WINIT_CLIENT_ID'],
    'WINIT_CLIENT_SECRET': ['CYT_DTC_WINIT_CLIENT_SECRET'],
}
for account in ('epomaker', 'linsoul'):
    for suffix in ('access_id', 'public_key'):
        ALIASES[f'QUICKCEP_{account.upper()}_{suffix.upper()}'] = [f'cs_quickcep_{account}_{suffix}']


class ImportFailure(Exception):
    pass


def cli():
    path = shutil.which('gcloud.cmd') or shutil.which('gcloud')
    if not path:
        raise ImportFailure('Google Cloud CLI was not found')
    return path


def gcloud(*args, payload=False):
    result = subprocess.run([cli(), *args, '--quiet'], capture_output=True, timeout=90)
    if result.returncode:
        # Never echo a failed subprocess's output: payload commands are sensitive.
        raise ImportFailure('gcloud command failed; check login, resource and access permissions')
    raw = result.stdout.decode('utf-8')
    return raw if payload else json.loads(raw)


def normalize(name):
    return name.upper().replace('-', '_')


def allowed_patterns():
    contract = json.loads((ROOT / 'src/main/resources/contract.json').read_text(encoding='utf-8'))
    business_file = ROOT / 'src/main/resources/business-contract.json'
    if business_file.is_file():
        contract['credentials'] = [*contract['credentials'], *json.loads(business_file.read_text(encoding='utf-8')).get('credentials', [])]
    patterns, declared = [], set()
    for platform in contract['credentials']:
        names = [platform.get('accounts_var', ''), platform.get('default_account_var', '')]
        for variable in platform.get('vars', []):
            names.extend([variable['name'], variable.get('scoped', '')])
        for name in filter(None, names):
            declared.add(name.replace('{ACCOUNT}', 'DEFAULT'))
            pattern = re.escape(name).replace(re.escape('{ACCOUNT}'), '[A-Z0-9_]+').replace(re.escape('{SYSTEM}'), '[A-Z0-9_]+')
            patterns.append(re.compile('^' + pattern + '$'))
    return patterns, declared


def allowed(name):
    return bool(re.fullmatch('[A-Z][A-Z0-9_]{0,63}', name)) and any(p.fullmatch(name) for p in allowed_patterns()[0])


def cloud_environment(args):
    service = gcloud('run', 'services', 'describe', args.service, '--project=' + args.project,
                     '--region=' + args.region, '--format=json')
    return service.get('spec', {}).get('template', {}).get('spec', {}).get('containers', [{}])[0].get('env', [])


def plan(args):
    _, declared = allowed_patterns()
    cloud = cloud_environment(args)
    listed = gcloud('secrets', 'list', '--project=' + args.project, '--format=json(name)')
    ids = {item['name'].split('/')[-1] for item in listed}
    normalized = {}
    for secret_id in ids:
        normalized.setdefault(normalize(secret_id), []).append(secret_id)
    bindings = {}
    # Exact bindings in the deployed gateway take precedence over name inference.
    for entry in cloud:
        name = entry.get('name', '')
        if not allowed(name):
            continue
        ref = entry.get('valueFrom', {}).get('secretKeyRef')
        if ref:
            bindings[name] = {'name': name, 'secret': ref['name'], 'requested_version': ref.get('key', 'latest'), 'source': 'secret_manager'}
        elif entry.get('value'):
            bindings[name] = {'name': name, 'source': 'cloud_run_configuration'}
    candidates = set(declared) | set(ALIASES)
    for secret_id in ids:
        name = normalize(secret_id)
        if allowed(name):
            candidates.add(name)
    ambiguous = []
    for name in sorted(candidates):
        if not allowed(name) or name in bindings:
            continue
        matches = [name] if name in ids else normalized.get(name, [])
        if not matches:
            for alias in ALIASES.get(name, []):
                matches = [alias] if alias in ids else normalized.get(normalize(alias), [])
                if matches:
                    break
        if len(matches) == 1:
            bindings[name] = {'name': name, 'secret': matches[0], 'requested_version': 'latest', 'source': 'secret_manager'}
        elif len(matches) > 1:
            ambiguous.append({'name': name, 'candidates': matches})
    errors = []
    version_cache = {}
    ready = []
    for binding in bindings.values():
        if binding['source'] == 'secret_manager':
            key = (binding['secret'], binding['requested_version'])
            try:
                if key not in version_cache:
                    version_cache[key] = gcloud('secrets', 'versions', 'describe', key[1], '--secret=' + key[0], '--project=' + args.project, '--format=json(name,state)')
                version = version_cache[key]
                if version.get('state') != 'ENABLED':
                    raise ImportFailure('Secret version is not enabled')
                binding['version'] = version['name'].split('/')[-1]
                if not binding['version'].isdigit():
                    raise ImportFailure('A numeric secret version is required')
            except ImportFailure:
                errors.append({'name': binding['name'], 'secret': binding['secret'], 'reason': 'version_unavailable'})
                continue
        ready.append(binding)
    result = {'project': args.project, 'service': args.service, 'region': args.region, 'target_environment': getattr(args, 'env', 'local'), 'generated_at': datetime.now(timezone.utc).isoformat(),
              'bindings': sorted(ready, key=lambda b: b['name']), 'ambiguous': ambiguous, 'unavailable': errors}
    args.plan_file.parent.mkdir(parents=True, exist_ok=True)
    args.plan_file.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'plan_file': str(args.plan_file), 'matched': len(ready), 'ambiguous': ambiguous, 'unavailable': errors,
                      'names': [b['name'] for b in result['bindings']]}, ensure_ascii=False))


def properties(path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')) or '=' not in line:
            continue
        name, value = line.split('=', 1)
        result[name.strip()] = re.sub(r'\\([\\=: ])', r'\1', value.strip())
    return result


def local_connection(config):
    import pymysql
    url = urlsplit(config.get('spring.datasource.url', '').removeprefix('jdbc:'))
    database = url.path.lstrip('/')
    if url.scheme != 'mysql' or url.hostname not in ('localhost', '127.0.0.1', '::1') or not database.startswith('cyt_common'):
        raise ImportFailure('Import is restricted to an explicitly configured local cyt_common MySQL database')
    return pymysql.connect(host=url.hostname, port=url.port or 3306, database=database,
                           user=config.get('spring.datasource.username', ''), password=config.get('spring.datasource.password', ''),
                           charset='utf8mb4', autocommit=False, init_command="SET time_zone = '+00:00'")


def validate_environment_target(config, document, args):
    if config.get('ENV', 'local') != 'local' or args.env not in ('local', 'staging', 'test', 'production'):
        raise ImportFailure('Import requires a local development connection and an existing environment partition')
    planned = document.get('target_environment')
    if planned is not None and planned != args.env:
        raise ImportFailure('Plan environment does not match the explicit import target')
    if args.env == 'production' and (not getattr(args, 'allow_production', False) or planned != 'production'):
        raise ImportFailure('The production partition requires a production-targeted plan and --allow-production')


def apply(args):
    from cryptography.fernet import Fernet
    if not args.plan_file.is_file():
        raise ImportFailure('Generate and review a plan before applying it')
    document = json.loads(args.plan_file.read_text(encoding='utf-8'))
    if (document.get('project'), document.get('service'), document.get('region')) != (args.project, args.service, args.region):
        raise ImportFailure('Plan cloud target does not match the explicit command target')
    bindings = document.get('bindings', [])
    for binding in bindings:
        if not allowed(binding['name']) or binding.get('source') not in ('secret_manager', 'cloud_run_configuration'):
            raise ImportFailure('Plan contains an unregistered platform credential')
        if binding['source'] == 'secret_manager' and (not re.fullmatch('[A-Za-z0-9_-]+', binding.get('secret', '')) or not str(binding.get('version', '')).isdigit()):
            raise ImportFailure('Plan must use a secret ID and a pinned numeric version')
    local_file = ROOT / 'application-local.properties'
    if not local_file.is_file():
        raise ImportFailure('Configure application-local.properties before importing')
    config = properties(local_file)
    validate_environment_target(config, document, args)
    connection = local_connection(config)
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT COUNT(*) FROM gateway_environment WHERE `key`=%s', (args.env,))
            if cursor.fetchone()[0] != 1:
                raise ImportFailure('Target environment does not exist')
            master = os.environ.get('CREDENTIAL_MASTER_KEY') or config.get('CREDENTIAL_MASTER_KEY', '')
            if not master.strip():
                cursor.execute('SELECT COUNT(*) FROM gateway_secret')
                if cursor.fetchone()[0]:
                    raise ImportFailure('Existing encrypted credentials require their original master key')
                master = secrets.token_urlsafe(48)
                text = local_file.read_text(encoding='utf-8-sig')
                text = re.sub(r'(?m)^CREDENTIAL_MASTER_KEY=.*(?:\r?\n|$)', '', text)
                temporary = ROOT / 'target' / ('local-bootstrap-' + secrets.token_hex(8) + '.properties')
                temporary.parent.mkdir(parents=True, exist_ok=True)
                try:
                    temporary.write_text(text.rstrip() + '\nCREDENTIAL_MASTER_KEY=' + master + '\n', encoding='utf-8')
                    os.replace(temporary, local_file)
                finally:
                    temporary.unlink(missing_ok=True)
                print('Local encryption master key configured; restart the Java service to load it.')
            cipher = Fernet(base64.urlsafe_b64encode(hashlib.sha256(master.strip().encode()).digest()))
            cursor.execute('SELECT ciphertext FROM gateway_secret LIMIT 1')
            existing = cursor.fetchone()
            if existing:
                try:
                    cipher.decrypt(existing[0].encode())
                except Exception:
                    raise ImportFailure('The configured master key cannot decrypt the existing credential store') from None
            literals = None
            changed, unchanged, retained, entries = [], [], [], []
            # Fetch every selected payload before changing database rows.
            values = []
            for binding in bindings:
                if binding['source'] == 'secret_manager':
                    try:
                        value = gcloud('secrets', 'versions', 'access', str(binding['version']), '--secret=' + binding['secret'], '--project=' + args.project, payload=True)
                    except ImportFailure:
                        raise ImportFailure('Cannot access ' + binding['name'] + ' at its pinned cloud version; no payload has been logged') from None
                else:
                    if literals is None:
                        literals = {e['name']: e.get('value', '') for e in cloud_environment(args)}
                    value = literals.get(binding['name'], '')
                if not value.strip() or len(value) > 1_000_000:
                    raise ImportFailure('An imported credential is empty or exceeds the permitted size')
                values.append((binding, value))
            for binding, value in values:
                name = binding['name']
                cursor.execute('SELECT id,ciphertext FROM gateway_secret WHERE env=%s AND name=%s FOR UPDATE', (args.env, name))
                old = cursor.fetchone()
                if old:
                    if cipher.decrypt(old[1].encode()).decode() == value:
                        unchanged.append(name); continue
                    if not args.overwrite:
                        retained.append(name); continue
                token = cipher.encrypt(value.encode()).decode()
                if old:
                    cursor.execute('UPDATE gateway_secret SET ciphertext=%s,updated_by=%s,updated_at=UTC_TIMESTAMP(6) WHERE id=%s', (token, 'gcloud-import', old[0]))
                else:
                    cursor.execute('INSERT INTO gateway_secret (env,name,ciphertext,updated_by) VALUES (%s,%s,%s,%s)', (args.env, name, token, 'gcloud-import'))
                changed.append(name)
                entries.append({k: binding[k] for k in ('name', 'source', 'secret', 'version') if k in binding})
            if changed:
                detail = json.dumps({'project': args.project, 'service': args.service, 'entries': entries, 'names': changed}, ensure_ascii=False)
                cursor.execute('INSERT INTO gateway_admin_audit (actor,action,target,detail) VALUES (%s,%s,%s,%s)', ('gcloud-import', 'secrets_import_gcloud', args.env, detail))
            connection.commit()
            print(json.dumps({'environment': args.env, 'imported': len(changed), 'unchanged': len(unchanged), 'existing_values_retained': retained, 'names': changed, 'plaintext_logged': False}, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['plan', 'apply'])
    parser.add_argument('--project', default='cyt-digital')
    parser.add_argument('--service', default='cyt-common-api')
    parser.add_argument('--region', default='us-central1')
    parser.add_argument('--plan-file', type=Path, default=DEFAULT_PLAN)
    parser.add_argument('--env', default='local')
    parser.add_argument('--allow-production', action='store_true', help='Import into the explicitly selected production partition of the local credential store')
    parser.add_argument('--overwrite', action='store_true', help='Replace different local values after reviewing the plan')
    args = parser.parse_args()
    try:
        plan(args) if args.command == 'plan' else apply(args)
    except ImportFailure as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception as error:
        print('Credential operation failed (' + type(error).__name__ + '); payloads have not been printed.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
