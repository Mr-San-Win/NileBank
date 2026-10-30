"""End-to-end check of a running local NileBank demo; Python standard library only."""
import http.cookiejar
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

class Browser:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        self.csrf = None

    def request(self, method, path, body=None, key=None, expected=200, protect=True):
        headers = {"Accept": "application/json"}
        if method != "GET" and self.csrf and protect:
            headers[self.csrf["headerName"]] = self.csrf["token"]
        if key:
            headers["Idempotency-Key"] = key
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = urllib.request.Request(BASE_URL + "/api/v1" + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            assert response.status == expected, (method, path, response.status, content.decode())
            return json.loads(content) if content else None

    def token(self):
        self.csrf = self.request("GET", "/auth/csrf")

    def account(self):
        self.token()
        email = "smoke-" + str(uuid.uuid4()) + "@example.test"
        password = "Demo-" + str(uuid.uuid4())
        self.request("POST", "/auth/register", {"fullName": "Smoke Demo Student", "email": email, "password": password}, expected=201)
        self.request("POST", "/auth/login", {"email": email, "password": password})
        self.token()
        return self.request("POST", "/accounts", expected=201)

def main():
    sender, recipient = Browser(), Browser()
    assert sender.request("GET", "/health")["status"] == "UP"
    sender.request("POST", "/auth/login", {"email": "invalid@example.test", "password": "invalid"}, expected=403, protect=False)
    source, destination = sender.account(), recipient.account()
    assert source["balance"] == "0.00"
    assert source["accountNumber"].startswith("NB")
    sender.request("POST", "/transactions/deposit", {"accountId": source["id"], "amount": "100.50"}, expected=201)
    sender.request("POST", "/transactions/withdraw", {"accountId": source["id"], "amount": "10.25"}, expected=201)
    payload = {"sourceAccountId": source["id"], "recipientAccountNumber": destination["accountNumber"], "amount": "20.25"}
    key = str(uuid.uuid4())
    first = sender.request("POST", "/transfers", payload, key=key)
    replay = sender.request("POST", "/transfers", payload, key=key)
    assert first["id"] == replay["id"] and first["reference"].startswith("NB-")
    assert sender.request("GET", "/accounts/me")["balance"] == "70.00"
    assert recipient.request("GET", "/accounts/me")["balance"] == "20.25"
    assert sender.request("GET", "/transactions")["totalElements"] == 3
    recipient.request("POST", "/transactions/withdraw", {"accountId": source["id"], "amount": "1.00"}, expected=404)
    sender.request("POST", "/auth/logout", expected=204)
    sender.request("GET", "/auth/me", expected=401)
    print("PASS: NileBank Docker HTTP journey; CSRF, sessions, deposit, withdrawal, transfer, idempotent replay, ownership, history, logout, and NB identifiers.")

if __name__ == "__main__":
    main()

