"""Full extension smoke test against the real local app and real published FX data."""
from decimal import Decimal, ROUND_DOWN
from datetime import datetime
from zoneinfo import ZoneInfo
import uuid
from smoke import Browser

def main():
    user = Browser()
    account = user.account()
    user.request("POST", "/transactions/deposit", {"accountId": account["id"], "amount": "1000.00"}, expected=201)
    first = user.request("GET", "/exchange/rates")
    second = user.request("GET", "/exchange/rates")
    assert first["mode"] == "REAL_REFERENCE_RATES"
    assert first["provider"] == "Frankfurter / European Central Bank"
    assert first["fetchedAt"] != second["fetchedAt"]
    quote = user.request("POST", "/exchange/quotes", {"fromCurrency": "THB", "toCurrency": "USD", "amount": "350.00"})
    assert quote["provider"] == first["provider"] and quote["rateDate"]
    expected = (Decimal("350") * Decimal(quote["rate"])).quantize(Decimal("0.01"), rounding=ROUND_DOWN)
    assert Decimal(quote["receivedAmount"]) == expected
    executed = user.request("POST", "/exchange/quotes/" + quote["id"] + "/execute")
    repeat = user.request("POST", "/exchange/quotes/" + quote["id"] + "/execute")
    assert executed["transactionId"] == repeat["transactionId"]
    wallets = {w["currency"]: w["balance"] for w in user.request("GET", "/exchange/wallets")}
    assert wallets["THB"] == "650.00" and Decimal(wallets["USD"]) == expected
    connection = user.request("POST", "/partners/connections", {"bankCode": "HARBOR", "demoConsent": True})
    key = str(uuid.uuid4())
    payload = {"connectionId": connection["id"], "direction": "IN", "amount": "100.00"}
    move = user.request("POST", "/partners/transfers", payload, key=key)
    assert user.request("POST", "/partners/transfers", payload, key=key)["id"] == move["id"]
    user.request("POST", "/partners/transfers", {"connectionId": connection["id"], "direction": "OUT", "amount": "50.00"}, key=str(uuid.uuid4()))
    user.request("POST", "/partners/connections/" + connection["id"] + "/disconnect")
    reconnect = user.request("POST", "/partners/connections", {"bankCode": "HARBOR", "demoConsent": True})
    assert reconnect["balance"] == "4950.00"
    assert user.request("GET", "/accounts/me")["balance"] == "700.00"
    today = datetime.now(ZoneInfo("Asia/Bangkok")).date().isoformat()
    month = today[:7]
    user.request("POST", "/finance/entries", {"direction": "INCOME", "category": "SALARY", "amount": "20000.00", "description": "Smoke salary", "occurredOn": today}, expected=201)
    expense = user.request("POST", "/finance/entries", {"direction": "EXPENSE", "category": "FOOD", "amount": "500.00", "description": "Smoke food", "occurredOn": today}, expected=201)
    user.request("POST", "/finance/budgets", {"month": month, "category": "FOOD", "amount": "400.00"}, expected=204)
    summary = user.request("GET", "/finance/summary?month=" + month)
    assert summary["income"] == "20000.00" and summary["expenses"] == "500.00" and summary["net"] == "19500.00"
    assert summary["savingsRate"] == "97.5"
    assert any("over budget" in item["title"] for item in summary["insights"])
    goal = user.request("POST", "/finance/goals", {"name": "Smoke emergency fund", "target": "1000.00"}, expected=201)
    allocated = user.request("POST", "/finance/goals/" + goal["id"] + "/allocations", {"amount": "250.00"})
    assert allocated["percent"] == "25" and allocated["remaining"] == "750.00"
    assert user.request("GET", "/accounts/me")["balance"] == "700.00"
    other = Browser()
    other.account()
    assert other.request("GET", "/finance/entries?month=" + month)["totalElements"] == 0
    other.request("DELETE", "/finance/entries/" + expense["id"], expected=404)
    user.request("DELETE", "/finance/entries/" + expense["id"], expected=204)
    assert user.request("GET", "/finance/summary?month=" + month)["expenses"] == "0.00"
    user.request("POST", "/auth/logout", expected=204)
    print("PASS: real FX refresh/quote/execution/replay, foreign wallets, simulated bank movements/reconnect, finance entries/budgets/insights/goals, isolation and logout.")
    print("Verified rate source:", first["provider"], "publication:", first["rateDate"], "retrieved:", second["fetchedAt"])

if __name__ == "__main__":
    main()
