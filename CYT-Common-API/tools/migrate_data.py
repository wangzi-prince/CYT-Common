"""Copy reference data into an empty MySQL deployment. Dry-run is the default."""
import argparse
import asyncio
import os
import re
from sqlalchemy import MetaData, Table, create_engine, func, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import create_async_engine

TABLES = (
    'gateway_system', 'gateway_grant', 'gateway_call_log', 'gateway_grant_audit',
    'gateway_idempotency', 'gateway_system_apikey', 'gateway_admin_audit',
    'gateway_module', 'gateway_custom_api', 'gateway_op_category', 'gateway_op_order',
    'gateway_environment', 'gateway_secret', 'gateway_callback',
)

async def migrate(apply: bool):
    source_url = os.environ.get('SOURCE_DATABASE_URL', '')
    target_url = os.environ.get('TARGET_DATABASE_URL', '')
    if not source_url or not target_url:
        raise ValueError('Set SOURCE_DATABASE_URL and TARGET_DATABASE_URL; passwords are never printed.')
    source = make_url(source_url)
    target = make_url(target_url)
    if source.get_backend_name() == 'postgresql':
        source = source.set(drivername='postgresql+asyncpg')
    elif source.get_backend_name() == 'sqlite':
        source = source.set(drivername='sqlite+aiosqlite')
    else:
        raise ValueError('Source must be PostgreSQL or SQLite.')
    if target.get_backend_name() != 'mysql' or not re.fullmatch(r'cyt_common[a-zA-Z0-9_]*', target.database or ''):
        raise ValueError('Target must be an independent MySQL database named cyt_common*.')
    target = target.set(drivername='mysql+pymysql')
    schema = os.environ.get('SOURCE_DB_SCHEMA') or None
    if schema and source.get_backend_name() != 'postgresql':
        raise ValueError('SOURCE_DB_SCHEMA only applies to PostgreSQL.')
    source_engine = create_async_engine(source)
    target_engine = create_engine(target, connect_args={'init_command': "SET time_zone = '+00:00'"})
    try:
        async with source_engine.connect() as source_connection:
            if source.get_backend_name() == 'postgresql':
                source_connection = await source_connection.execution_options(isolation_level='REPEATABLE READ')
            async with source_connection.begin():
                if source.get_backend_name() == 'postgresql':
                    await source_connection.execute(text('SET TRANSACTION READ ONLY'))
                source_metadata = MetaData(schema=schema)
                await source_connection.run_sync(lambda c: source_metadata.reflect(c, only=TABLES))
                with target_engine.begin() as target_connection:
                    target_metadata = MetaData()
                    target_metadata.reflect(target_connection, only=TABLES)
                    for name in TABLES:
                        count = target_connection.scalar(select(func.count()).select_from(target_metadata.tables[name]))
                        if count:
                            raise ValueError(f'Target table {name} contains data; refusing to overwrite.')
                    for name in TABLES:
                        source_table = source_metadata.tables[f'{schema}.{name}' if schema else name]
                        target_table = target_metadata.tables[name]
                        count = await source_connection.scalar(select(func.count()).select_from(source_table))
                        print(f'{name}: {count} rows' + ('; copying' if apply else '; dry-run'))
                        if not apply:
                            continue
                        columns = set(target_table.c.keys())
                        copied = 0
                        stream = await source_connection.stream(select(source_table))
                        async for batch in stream.mappings().partitions(1000):
                            rows = [{k: v for k, v in row.items() if k in columns} for row in batch]
                            if rows:
                                target_connection.execute(target_table.insert(), rows)
                                copied += len(rows)
                        actual = target_connection.scalar(select(func.count()).select_from(target_table))
                        if actual != count or copied != count:
                            raise RuntimeError(f'Row count mismatch in {name}; target transaction will roll back.')
        print('Copy committed.' if apply else 'Preflight complete. No records copied. Use --apply only after reviewing the target.')
    finally:
        await source_engine.dispose()
        target_engine.dispose()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Copy into the empty target; otherwise only inspect.')
    arguments = parser.parse_args()
    try:
        asyncio.run(migrate(arguments.apply))
    except Exception as error:
        # Driver exceptions may contain DSNs or SQL payloads. Print only our deliberate validation messages.
        print(f'Migration failed: {error}' if type(error) is ValueError else f'Migration failed: {type(error).__name__}; target transaction rolled back if opened.')
        raise SystemExit(1)
