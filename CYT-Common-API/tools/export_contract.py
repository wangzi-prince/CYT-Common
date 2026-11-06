"""Development-only contract snapshot. No database access or credential values."""
import ast
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import get_args

from pydantic import BaseModel

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path('D:/code/cyt-common-api')
sys.path.insert(0, str(ROOT))
from gateway import registry, models, naming, credentials_spec
from gateway.seed import SYSTEMS, GRANTS
from sqlalchemy import Boolean, DateTime, Float, Integer, String, Text, UniqueConstraint


def referenced_models(annotation):
    """Unwrap Optional/list/dict annotations down to the BaseModel classes they carry."""
    args = get_args(annotation)
    if args:
        found = []
        for arg in args:
            found.extend(referenced_models(arg))
        return found
    return [annotation] if isinstance(annotation, type) and issubclass(annotation, BaseModel) else []


def inject_factory_defaults(model, schema):
    """Pydantic omits default_factory values from JSON Schema, so a nested list field would
    arrive at the upstream without the [] the original gateway always sent. Walk every
    reachable model, not just the top level."""
    defs = schema.get('$defs', {})
    pending = [(model, schema)]
    seen = set()
    while pending:
        current_model, current_schema = pending.pop()
        if id(current_model) in seen:
            continue
        seen.add(id(current_model))
        properties = current_schema.get('properties', {})
        for name, field in current_model.model_fields.items():
            prop = properties.get(name)
            if prop is None:
                continue
            if 'default' not in prop and field.default_factory in (list, dict):
                prop['default'] = field.default_factory()
            for nested in referenced_models(field.annotation):
                definition = next(
                    (node for key, node in defs.items() if key == nested.__name__ or key.endswith('.' + nested.__name__)),
                    None,
                )
                if definition and 'properties' in definition:
                    pending.append((nested, definition))


DEST = Path(__file__).resolve().parents[1] / 'src/main/resources'
DEST.mkdir(parents=True, exist_ok=True)
ops = []
for op in registry.all_operations():
    d = {k: getattr(op, k) for k in ('key', 'platform', 'summary', 'quota', 'sensitive', 'idempotent', 'timeout_s', 'retry_max', 'since', 'deprecated', 'description', 'tags', 'cost', 'category', 'sort_order')}
    d['schema'] = op.schema()
    inject_factory_defaults(op.params_model, d['schema'])
    d['path'] = naming.operation_path(op.key)
    ops.append(d)
platforms = [{'platform': a.platform, 'display_name': a.display_name, 'platform_quota': a.platform_quota} for a in registry.PLATFORMS.values() if a.platform != 'custom']
contract = {'operations': ops, 'platforms': platforms, 'aliases': naming.LEGACY_OPERATION_ALIASES,
            'credentials': [asdict(c) for c in credentials_spec.PLATFORM_CREDS],
            'systems': [{'key': k, 'display_name': n, 'description': d} for k, n, d in SYSTEMS],
            'grants': [{'system': s, 'operation_pattern': naming.canonical_operation_key(p), 'quota': q, 'note': n} for s, p, q, n in GRANTS]}
(DEST / 'contract.json').write_text(json.dumps(contract, ensure_ascii=False, indent=2), encoding='utf-8')
from gateway.adapters import shopify
queries = {name: value for name, value in vars(shopify).items() if name.startswith('_') and name.isupper() and isinstance(value, str) and ('QUERY' in name or 'MUTATION' in name)}
(DEST / 'shopify-queries.json').write_text(json.dumps(queries, ensure_ascii=False, indent=2), encoding='utf-8')

ddl = ['-- Generated from the original SQLAlchemy models. MySQL 8.0.13+ / 8.4.\n-- Identifiers and idempotency keys are case-sensitive. All timestamps are UTC.']
for table in models.Base.metadata.sorted_tables:
    cols = []
    for c in table.columns:
        if isinstance(c.type, Boolean): typ = 'BOOLEAN'
        elif isinstance(c.type, Integer): typ = 'BIGINT' if c.primary_key else 'INT'
        elif isinstance(c.type, Float): typ = 'DOUBLE'
        elif isinstance(c.type, DateTime): typ = 'DATETIME(6)'
        elif isinstance(c.type, Text): typ = 'LONGTEXT'
        elif isinstance(c.type, String): typ = f'VARCHAR({c.type.length})'
        else: raise ValueError(c.type)
        line = f'  `{c.name}` {typ}'
        if not c.nullable: line += ' NOT NULL'
        if c.primary_key: line += ' AUTO_INCREMENT PRIMARY KEY'
        elif c.default is not None:
            val = c.default.arg
            if callable(val): line += ' DEFAULT CURRENT_TIMESTAMP(6)'
            elif isinstance(val, bool): line += f' DEFAULT {1 if val else 0}'
            elif isinstance(val, (int, float)): line += f' DEFAULT {val}'
            else:
                val = str(val).replace("'", "''")
                line += f" DEFAULT ('{val}')" if isinstance(c.type, Text) else f" DEFAULT '{val}'"
        cols.append(line)
    for c in table.constraints:
        if isinstance(c, UniqueConstraint):
            cols.append('  UNIQUE (' + ','.join(f'`{col.name}`' for col in c.columns) + ')')
    for idx in sorted(table.indexes, key=lambda x: x.name):
        cols.append('  ' + ('UNIQUE ' if idx.unique else '') + f'INDEX `{idx.name}` (' + ','.join(f'`{col.name}`' for col in idx.columns) + ')')
    ddl.append(f'CREATE TABLE `{table.name}` (\n' + ',\n'.join(cols) + '\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
# Atomic claim BEFORE upstream effects, shared across replicas; old successful records migrate unchanged.
ddl.append("ALTER TABLE gateway_idempotency ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'complete';")
(DEST / 'db/migration').mkdir(parents=True, exist_ok=True)
(DEST / 'db/migration/V1__gateway.sql').write_text('\n\n'.join(ddl) + '\n', encoding='utf-8')
print(f'Exported {len(ops)} operations, {len(platforms)} platforms, {len(models.Base.metadata.tables)} tables; no secrets exported.')
