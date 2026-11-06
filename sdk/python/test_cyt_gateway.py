import io
import json
import unittest
from datetime import date
from decimal import Decimal
from unittest.mock import patch
from urllib.error import HTTPError
from cyt_gateway import Gateway, GatewayError, CollectionLimitError


def reply(data):
    return io.BytesIO(json.dumps({'ok': True, 'data': data}).encode())


class GatewayClientTests(unittest.TestCase):
    def setUp(self):
        self.client = Gateway('http://127.0.0.1:8189', 'fixture-api-key')

    @patch('cyt_gateway.urlopen')
    def test_submit_preserves_business_id_decimal_and_date(self, open_url):
        open_url.return_value = reply({'approval_no': 'MOCK-1'})
        result = self.client.approvals.submit('expense', 'fixture-user', {'amount': Decimal('1.20'), 'date': date(2026, 11, 5)}, request_id='order-001')
        self.assertEqual('MOCK-1', result['approval_no'])
        request = open_url.call_args.args[0]
        self.assertEqual('order-001', request.get_header('Idempotency-key'))
        self.assertEqual('Bearer fixture-api-key', request.get_header('Authorization'))
        body = json.loads(request.data)
        self.assertEqual('order-001', body['request_id'])
        self.assertEqual({'amount': '1.20', 'date': '2026-11-05'}, body['fields'])

    @patch('cyt_gateway.urlopen')
    def test_template_resource_uses_public_hyphenated_path(self, open_url):
        open_url.return_value = reply({'fields': []})
        self.client.approvals.form('expense')
        self.assertTrue(open_url.call_args.args[0].full_url.endswith('/integrations/wecom/approval-templates/get'))

    @patch('cyt_gateway.urlopen')
    def test_error_retains_trace_and_does_not_retry_write(self, open_url):
        open_url.side_effect = HTTPError('http://127.0.0.1', 409, 'Conflict', {}, io.BytesIO(b'{"error":{"type":"idempotency_in_progress","trace_id":"fixture-trace","message":"uncertain"}}'))
        with self.assertRaises(GatewayError) as error:
            self.client.approvals.submit('expense', 'fixture-user', {}, request_id='stable-id')
        self.assertEqual('fixture-trace', error.exception.trace_id)
        self.assertEqual(1, open_url.call_count)

    @patch('cyt_gateway.urlopen')
    def test_empty_page_continues_by_cursor(self, open_url):
        open_url.side_effect = [reply({'items': [], 'page': {'has_more': True, 'next_cursor': 'next'}}), reply({'items': [{'id': 'A'}], 'page': {'has_more': False, 'next_cursor': None}})]
        pages = list(self.client.collect('gdrive.files.search'))
        self.assertEqual(2, len(pages))
        self.assertEqual('next', json.loads(open_url.call_args.args[0].data)['resume_cursor'])

    @patch('cyt_gateway.urlopen')
    def test_limit_exposes_continuation_instead_of_silent_truncation(self, open_url):
        open_url.return_value = reply({'items': [], 'page': {'has_more': True, 'next_cursor': 'continue'}})
        with self.assertRaises(CollectionLimitError) as error:
            list(self.client.collect('wecom.approvals.collect', max_requests=1))
        self.assertEqual('continue', error.exception.next_cursor)


if __name__ == '__main__':
    unittest.main()
