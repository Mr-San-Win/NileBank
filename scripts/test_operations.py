"""Offline probe policy tests. Run: python3 -m unittest discover -s scripts -p test_operations.py"""
import io
import unittest
from unittest.mock import patch
from check_health import check


class Response(io.BytesIO):
    status = 200


class ProbeTests(unittest.TestCase):
    def probe(self, body, status=200):
        response = Response(body)
        response.status = status
        with patch("urllib.request.OpenerDirector.open", return_value=response):
            return check("https://nilebank.example")

    def test_ready(self):
        self.assertEqual({"status": "UP"}, self.probe(b'{"status":"UP"}'))

    def test_down(self):
        with self.assertRaises(ValueError):
            self.probe(b'{"status":"DOWN"}')

    def test_non_success(self):
        with self.assertRaises(ValueError):
            self.probe(b'{"status":"UP"}', 503)

    def test_invalid_json(self):
        with self.assertRaises(ValueError):
            self.probe(b'<html>error</html>')

    def test_size_limit(self):
        with self.assertRaises(ValueError):
            self.probe(b' ' * 16385)

    def test_reject_unsafe_origins(self):
        for url in ("http://localhost", "https://u:p@example.com", "https://example.com/?token=x",
                    "https://example.com/#secret", "https://example.com/path", "file:///tmp/test"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                check(url)


if __name__ == "__main__":
    unittest.main()
