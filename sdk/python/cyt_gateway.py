"""Small standard-library client for CYT Common business capabilities (Python 3.10+)."""
from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Iterator
from urllib.error import HTTPError
from urllib.request import Request, urlopen


class GatewayError(RuntimeError):
    def __init__(self, status: int, error: dict[str, Any]):
        super().__init__(error.get('message', 'Gateway request failed'))
        self.status = status
        self.code = error.get('code', error.get('type', 'gateway_error'))
        self.trace_id = error.get('trace_id')
        self.retryable = bool(error.get('retryable'))
        self.detail = error.get('detail')


class CollectionLimitError(RuntimeError):
    def __init__(self, next_cursor: str):
        super().__init__('Reached max_requests; resume explicitly using next_cursor')
        self.next_cursor = next_cursor


def _json(value: Any) -> str:
    def encode(item: Any) -> str:
        if isinstance(item, (datetime, date)):
            return item.isoformat()
        if isinstance(item, Decimal):
            return str(item)
        raise TypeError(f'Unsupported JSON value: {type(item).__name__}')
    return json.dumps(value, ensure_ascii=False, default=encode)


class Gateway:
    def __init__(self, base_url: str, api_key: str, *, timeout: float = 100):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.timeout = timeout
        self.approvals = Approvals(self)

    def call(self, operation: str, params: dict[str, Any] | None = None, *, request_id: str | None = None) -> Any:
        parts = operation.split('.')
        if len(parts) != 3 or not all(part.replace('_', '').isalnum() for part in parts):
            raise ValueError('operation must be platform.resource.action')
        headers = {'Authorization': f'Bearer {self.api_key}', 'Content-Type': 'application/json', 'Accept': 'application/json'}
        if request_id:
            headers['Idempotency-Key'] = request_id
        path = '/integrations/' + '/'.join(part.replace('_', '-') for part in parts)
        request = Request(self.base_url + path, data=_json(params or {}).encode('utf-8'), headers=headers, method='POST')
        try:
            with urlopen(request, timeout=self.timeout) as response:
                envelope = json.load(response)
        except HTTPError as error:
            try:
                payload = json.load(error)
            except (ValueError, UnicodeError):
                payload = {'error': {'message': 'Gateway returned a non-JSON error'}}
            raise GatewayError(error.code, payload.get('error', {})) from None
        if not envelope.get('ok'):
            raise GatewayError(502, envelope.get('error', {}))
        return envelope['data']

    def collect(self, operation: str, *, max_requests: int = 10, **params: Any) -> Iterator[dict[str, Any]]:
        """Yield bounded batches; never infer completion from the number of returned rows."""
        if not 1 <= max_requests <= 100:
            raise ValueError('max_requests must be between 1 and 100')
        cursor_key = 'cursor' if operation == 'wecom.approvals.collect' else 'resume_cursor'
        cursor = params.pop(cursor_key, None)
        seen: set[str] = set()
        for _ in range(max_requests):
            result = self.call(operation, {**params, **({cursor_key: cursor} if cursor else {})})
            yield result
            page = result.get('page', {})
            if not page.get('has_more'):
                return
            cursor = page.get('next_cursor')
            if not cursor or cursor in seen:
                raise RuntimeError('Gateway returned a missing or repeated continuation cursor')
            seen.add(cursor)
        raise CollectionLimitError(cursor)


class Approvals:
    def __init__(self, gateway: Gateway):
        self.gateway = gateway

    def templates(self) -> dict[str, Any]:
        return self.gateway.call('wecom.approval_templates.list')

    def form(self, template: str) -> dict[str, Any]:
        return self.gateway.call('wecom.approval_templates.get', {'template': template})

    def bind(self, alias: str, template_id: str, field_aliases: dict[str, str] | None = None) -> dict[str, Any]:
        return self.gateway.call('wecom.approval_templates.bind', {'alias': alias, 'template_id': template_id, 'field_aliases': field_aliases or {}})

    def create_template(self, name: str, fields: list[dict[str, Any]], *, request_id: str, alias: str | None = None) -> dict[str, Any]:
        params = {'name': name, 'fields': fields, 'request_id': request_id}
        if alias:
            params['alias'] = alias
        return self.gateway.call('wecom.approval_templates.create', params, request_id=request_id)

    def prepare(self, template: str, applicant: str, fields: dict[str, Any], **options: Any) -> dict[str, Any]:
        return self.gateway.call('wecom.approvals.prepare', {'template': template, 'applicant': applicant, 'fields': fields, **options})

    def submit(self, template: str, applicant: str, fields: dict[str, Any], *, request_id: str, **options: Any) -> dict[str, Any]:
        """Use a stable business request ID. No automatic retry of a write or uncertain result."""
        if not request_id.strip():
            raise ValueError('request_id must be a stable non-empty business identifier')
        return self.gateway.call('wecom.approvals.submit', {'template': template, 'applicant': applicant, 'fields': fields, **options, 'request_id': request_id}, request_id=request_id)

    def get(self, approval_no: str) -> dict[str, Any]:
        return self.gateway.call('wecom.approvals.get', {'approval_no': approval_no})

    def track(self, request_id: str) -> dict[str, Any]:
        """Resolve a submitted business request in this caller's environment and application."""
        return self.gateway.call('wecom.approvals.track', {'request_id': request_id})

    def get_many(self, approval_nos: list[str]) -> dict[str, Any]:
        return self.gateway.call('wecom.approvals.get_many', {'approval_nos': approval_nos})

    def query(self, **filters: Any) -> dict[str, Any]:
        return self.gateway.call('wecom.approvals.collect', filters)

    def events(self, after_id: int = 0, limit: int = 50) -> dict[str, Any]:
        return self.gateway.call('wecom.approval_events.list', {'after_id': after_id, 'limit': limit})

    def upload_file(self, filename: str, content: bytes) -> dict[str, Any]:
        import base64
        return self.gateway.call('wecom.approval_files.upload', {'filename': filename, 'content_base64': base64.b64encode(content).decode('ascii')})
