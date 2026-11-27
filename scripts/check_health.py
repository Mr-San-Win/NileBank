"""Read-only readiness probe; HTTPS certificate verification is mandatory."""
import argparse
import json
import ssl
import sys
import urllib.parse
import urllib.request


def check(origin, timeout=10, ca_file=None):
    url = urllib.parse.urlsplit(origin)
    if (url.scheme != "https" or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in ("", "/")):
        raise ValueError("A canonical HTTPS origin is required")
    context = ssl.create_default_context(cafile=ca_file)
    # Redirects must not send a readiness request to another origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPSHandler(context=context))
    with opener.open(origin.rstrip("/") + "/api/v1/health", timeout=timeout) as response:
        payload = response.read(16385)
        if len(payload) > 16384:
            raise ValueError("Oversized health response")
        data = json.loads(payload)
        if response.status != 200 or data.get("status") != "UP":
            raise ValueError("Application is not ready")
    return {"status": "UP"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin")
    parser.add_argument("--ca-file", help="Optional operator-managed certificate authority file")
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args()
    if not 0 < args.timeout <= 60:
        parser.error("timeout must be greater than zero and at most 60 seconds")
    try:
        result = check(args.origin, args.timeout, args.ca_file)
    except Exception:
        # Do not emit URLs, response bodies, TLS details or credentials into alerts.
        print(json.dumps({"status": "DOWN"}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
