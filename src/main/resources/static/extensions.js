export function createExtensions({ api, notify, money, node, getUser, getAccount, onMoneyChanged }) {
  const $ = (selector) => document.querySelector(selector);
  let generation = 0, quote = null, pendingBank = null, connections = [], categories = null;
  let financePage = 0, financeRequest = 0;
  const symbols = { THB: "฿", USD: "$", EUR: "€" };
  const label = (value) => value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, c => c.toUpperCase());
  const cash = (value, currency = "THB") => (symbols[currency] || currency + " ") + money(value);
  const today = () => {
    const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Bangkok", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
    return ["year", "month", "day"].map(type => parts.find(p => p.type === type).value).join("-");
  };
  function error(id, message = "") { $(id).textContent = message; $(id).hidden = !message; }
  function validAmount(id) {
    const value = $(id).value.trim();
    if (!/^[0-9]+(\.[0-9]{1,2})?$/.test(value) || Number(value) < .01 || Number(value) > 1000000)
      throw new Error("Use a positive amount with at most 2 decimal places, up to 1,000,000.");
    return value;
  }
  async function busy(button, action) {
    if (button.disabled) return;
    const text = button.textContent; button.disabled = true; button.textContent = "Please wait…";
    try { return await action(); } finally { button.disabled = false; button.textContent = text; }
  }
  function bind(formId, errorId, action) {
    $(formId).addEventListener("submit", async event => {
      event.preventDefault(); error(errorId);
      await busy($(formId).querySelector('button[type="submit"], button:not([type])'), async () => {
        try { await action(); } catch (e) { error(errorId, e.message); }
      });
    });
  }
  function option(select, text, value) { const o = node("option", text); o.value = value; select.append(o); }
  function stats(target, rows) {
    target.replaceChildren();
    rows.forEach(([title, value, detail, negative]) => {
      const card = node("div", undefined, "stat-card" + (negative ? " negative" : ""));
      card.append(node("span", title, "stat-label"), node("strong", value), node("small", detail));
      target.append(card);
    });
  }
  function clearQuote() { quote = null; $("#fx-review").hidden = true; }
  async function loadWallets() {
    const ticket = generation;
    const wallets = await api("/exchange/wallets");
    if (ticket !== generation || !getUser()) return;
    stats($("#fx-wallets"), wallets.map(w => [w.currency + " WALLET", cash(w.balance, w.currency), "Simulated funds"]));
  }
  async function refreshRates() {
    clearQuote();
    $("#fx-rate-status").textContent = "Fetching the latest published rates…";
    const ticket = generation;
    try {
      const data = await api("/exchange/rates");
      if (ticket !== generation || !getUser()) return;
      const text = data.rates.filter(r => r.currency !== "THB").map(r => "1 " + r.currency + " = " + money(r.thbPerUnit) + " THB").join("   ·   ");
      $("#fx-rate-status").textContent = text + " | " + data.provider + " | Published " + data.rateDate + " | Retrieved " + new Date(data.fetchedAt).toLocaleString() + ". Reference rates may remain unchanged until the next publication.";
      $("#fx-quote-button").disabled = false;
    } catch (e) {
      if (ticket !== generation) return;
      $("#fx-rate-status").textContent = e.message + " No fixed fallback is used.";
      $("#fx-quote-button").disabled = true; throw e;
    }
  }
  async function openExchange() {
    $("#fx-wallets").replaceChildren(node("p", "Loading wallets…", "muted"));
    await Promise.all([loadWallets(), refreshRates()]);
  }
  function details(target, values) {
    target.replaceChildren(); values.forEach(([name, value]) => target.append(node("dt", name), node("dd", value)));
  }
  function renderQuote(data) {
    quote = data;
    details($("#fx-details"), [
      ["You exchange", cash(data.amount, data.fromCurrency) + " " + data.fromCurrency],
      ["You receive", cash(data.receivedAmount, data.toCurrency) + " " + data.toCurrency],
      ["Applied rate", "1 " + data.fromCurrency + " = " + data.rate + " " + data.toCurrency],
      ["Source", data.provider], ["Published", data.rateDate], ["Fee", "0.00"],
    ]);
    $("#fx-expiry").textContent = "Valid until " + new Date(data.expiresAt).toLocaleTimeString() + ". Received amount rounds down to two decimal places.";
    $("#fx-review").hidden = false; $("#fx-confirm").disabled = false;
    $("#fx-review").scrollIntoView({ block: "nearest" });
  }
  async function confirmExchange() {
    if (!quote) return;
    await busy($("#fx-confirm"), async () => {
      error("#fx-error");
      try {
        const result = await api("/exchange/quotes/" + quote.id + "/execute", { method: "POST" });
        clearQuote(); $("#fx-form").reset();
        notify("Exchange completed. Reference: " + result.reference);
        try { await Promise.all([onMoneyChanged(), loadWallets()]); }
        catch { notify("Exchange completed. Refresh the page to reload balances.", true); }
      } catch (e) {
        error("#fx-error", e.message);
        if (e.status === 409 && e.message.includes("expired")) clearQuote();
      }
    });
  }
  function clearBankReview() { pendingBank = null; $("#partner-review").hidden = true; }
  async function loadPartners() {
    const ticket = generation;
    const [banks, items] = await Promise.all([api("/partners/banks"), api("/partners/connections")]);
    if (ticket !== generation || !getUser()) return;
    connections = items;
    const select = $("#partner-bank"); const chosen = select.value; select.replaceChildren();
    banks.forEach(bank => option(select, bank.name, bank.code));
    if (chosen) select.value = chosen;
    const destination = $("#partner-connection"); const current = destination.value; destination.replaceChildren();
    option(destination, "Choose a connected demo account", "");
    const cards = $("#partner-connections"); cards.replaceChildren();
    banks.forEach(bank => {
      const connection = items.find(c => c.bankCode === bank.code);
      const card = node("article", undefined, "connection-card");
      const heading = node("div", undefined, "panel-title");
      heading.append(node("h3", bank.name), node("span", connection?.status === "CONNECTED" ? "CONNECTED" : "NOT CONNECTED", "small-badge"));
      card.append(heading, node("strong", connection ? cash(connection.balance) : "Not linked", "connection-balance"), node("p", bank.description));
      if (connection?.status === "CONNECTED") {
        option(destination, bank.name + " · " + cash(connection.balance), connection.id);
        const button = node("button", "Disconnect demo bank", "secondary");
        button.type = "button";
        button.addEventListener("click", async () => busy(button, async () => {
          try {
            await api("/partners/connections/" + connection.id + "/disconnect", { method: "POST" });
            clearBankReview(); await loadPartners(); notify("Demo connection disconnected. Its simulated balance is preserved.");
          } catch (e) { notify(e.message, true); }
        }));
        card.append(button);
      } else card.append(node("p", connection ? "Reconnect below to reuse the same balance." : "Connect below; no real credentials are needed."));
      cards.append(card);
    });
    if (current && items.some(c => c.id === current && c.status === "CONNECTED")) destination.value = current;
  }
  function reviewBank() {
    const id = $("#partner-connection").value;
    const connection = connections.find(c => c.id === id && c.status === "CONNECTED");
    if (!connection) throw new Error("Choose a connected demo bank first.");
    const body = { connectionId: id, direction: $("#partner-direction").value, amount: validAmount("#partner-amount") };
    const fingerprint = JSON.stringify(body);
    if (pendingBank?.fingerprint !== fingerprint) pendingBank = { body, fingerprint, key: crypto.randomUUID() };
    $("#partner-review-details").textContent = cash(body.amount) + (body.direction === "IN" ? " from " + connection.bankName + " to NileBank." : " from NileBank to " + connection.bankName + ".") + " Simulated THB only. No fee.";
    $("#partner-review").hidden = false;
  }
  async function confirmBank() {
    if (!pendingBank) return;
    await busy($("#partner-confirm"), async () => {
      error("#partner-move-error");
      try {
        const result = await api("/partners/transfers", { method: "POST", body: pendingBank.body, key: pendingBank.key });
        clearBankReview(); $("#partner-move-form").reset();
        notify("Demo bank movement completed. Reference: " + result.reference);
        try { await Promise.all([onMoneyChanged(), loadPartners()]); }
        catch { notify("Movement completed. Refresh to reload balances.", true); }
      } catch (e) { error("#partner-move-error", e.message); }
    });
  }

  function setEntryCategories() {
    const select = $("#entry-category"), prior = select.value;
    select.replaceChildren();
    (categories?.[$("#entry-direction").value === "INCOME" ? "income" : "expense"] || []).forEach(c => option(select, label(c), c));
    if ([...select.options].some(o => o.value === prior)) select.value = prior;
  }
  function renderCategories(summary) {
    const target = $("#finance-categories"); target.replaceChildren();
    summary.categories.forEach(c => {
      if (c.spent === "0.00" && !c.budget) return;
      const row = node("div", undefined, "category-row" + (Number(c.percent || 0) > 100 ? " over" : ""));
      const heading = node("div", undefined, "panel-title");
      heading.append(node("strong", label(c.category)), node("span", cash(c.spent)));
      row.append(heading);
      if (c.budget) {
        const progress = node("progress"); progress.max = 100; progress.value = Math.min(100, Number(c.percent));
        progress.setAttribute("aria-label", label(c.category) + " budget used");
        row.append(progress, node("small", "Budget " + cash(c.budget) + " · " + (c.remaining.startsWith("-") ? cash(c.remaining.slice(1)) + " over limit" : cash(c.remaining) + " remaining")));
      } else row.append(node("small", "No monthly budget set."));
      target.append(row);
    });
    if (!target.children.length) target.append(node("p", "Record an expense or set a category budget to see the breakdown.", "empty-state"));
    const previous = node("p", "Previous month's recorded expenses: " + cash(summary.previousExpenses) + ". Periods may contain different numbers of recorded days.", "field-help");
    target.append(previous);
  }
  function renderInsights(items) {
    const target = $("#finance-insights"); target.replaceChildren();
    items.forEach(i => {
      const card = node("article", undefined, "insight " + (["positive", "warning", "info"].includes(i.level) ? i.level : "info"));
      card.append(node("h3", i.title), node("p", i.detail), node("small", "Why: " + i.basis)); target.append(card);
    });
  }
  function renderEntries(data) {
    const target = $("#finance-entries"); target.replaceChildren();
    if (!data.content.length) target.append(node("p", "No entries for this month. Record your first income or expense above.", "empty-state"));
    else {
      const wrapper = node("div", undefined, "table-scroll"), table = node("table", undefined, "transaction-table entry-table");
      const head = node("thead"), hrow = node("tr");
      ["ENTRY", "DATE", "AMOUNT", "ACTION"].forEach(text => hrow.append(node("th", text)));
      head.append(hrow); table.append(head); const body = node("tbody");
      data.content.forEach(e => {
        const row = node("tr"), first = node("td");
        first.append(node("strong", label(e.category)), node("p", e.description || label(e.direction), "field-help"));
        row.append(first, node("td", e.occurredOn), node("td", (e.direction === "INCOME" ? "+ " : "− ") + cash(e.amount), "amount" + (e.direction === "INCOME" ? " in" : "")));
        const action = node("td"), remove = node("button", "Remove", "text-button"); remove.type = "button";
        remove.setAttribute("aria-label", "Remove " + label(e.category) + " entry " + e.amount + " THB");
        remove.addEventListener("click", async () => {
          if (!window.confirm("Remove this tracker entry? Bank balances and financial transaction history will stay unchanged.")) return;
          await busy(remove, async () => {
            try {
              await api("/finance/entries/" + e.id, { method: "DELETE" });
              financePage = 0; await loadFinance(); notify("Tracker entry removed.");
            } catch (error) { notify(error.message, true); }
          });
        });
        action.append(remove); row.append(action); body.append(row);
      });
      table.append(body); wrapper.append(table); target.append(wrapper);
    }
    $("#finance-page").textContent = data.totalPages ? "Page " + (data.page + 1) + " of " + data.totalPages : "No entries";
    $("#finance-previous").disabled = data.page === 0;
    $("#finance-next").disabled = data.page + 1 >= data.totalPages;
  }
  function renderGoals(goals) {
    const target = $("#finance-goals"), select = $("#allocation-goal"), previous = select.value;
    target.replaceChildren(); select.replaceChildren(); option(select, "Choose a savings goal", "");
    goals.forEach(g => {
      const card = node("article", undefined, "connection-card goal-card");
      card.append(node("h3", g.name), node("strong", cash(g.saved), "connection-balance"));
      const progress = node("progress"); progress.max = 100; progress.value = Number(g.percent); progress.setAttribute("aria-label", g.name + " progress");
      card.append(progress, node("p", g.percent + "% of " + cash(g.target) + " · " + cash(g.remaining) + " remaining"));
      target.append(card); if (g.remaining !== "0.00") option(select, g.name + " · " + cash(g.remaining) + " remaining", g.id);
    });
    if ([...select.options].some(o => o.value === previous)) select.value = previous;
    if (!goals.length) target.append(node("p", "No savings goals yet. Create a goal below and record allocations as you plan.", "empty-state"));
  }
  async function loadFinance() {
    const ticket = generation, request = ++financeRequest, month = $("#finance-month").value;
    const [summary, entries, goals, catalog] = await Promise.all([
      api("/finance/summary?month=" + month),
      api("/finance/entries?month=" + month + "&page=" + financePage + "&size=10"),
      api("/finance/goals"),
      categories ? Promise.resolve(categories) : api("/finance/categories"),
    ]);
    if (ticket !== generation || request !== financeRequest || !getUser()) return;
    categories = catalog;
    setEntryCategories();
    const budgetSelect = $("#budget-category"), prior = budgetSelect.value; budgetSelect.replaceChildren();
    categories.expense.forEach(c => option(budgetSelect, label(c), c)); if (prior) budgetSelect.value = prior;
    stats($("#finance-totals"), [
      ["RECORDED INCOME", cash(summary.income), "Selected month"],
      ["RECORDED EXPENSES", cash(summary.expenses), summary.entryCount + " total tracker entries"],
      ["RECORDED CASH FLOW", cash(summary.net), "Income minus expenses", summary.net.startsWith("-")],
      ["SAVINGS RATE", summary.savingsRate === null ? "—" : summary.savingsRate + "%", summary.savingsRate === null ? "Record income to calculate" : "Recorded cash flow / income"],
    ]);
    renderCategories(summary); renderInsights(summary.insights); renderEntries(entries); renderGoals(goals);
  }
  function init() {
    $("#finance-month").value = today().slice(0, 7); $("#entry-date").value = today(); $("#entry-date").max = today();
    bind("#fx-form", "#fx-error", async () => {
      if ($("#fx-from").value === $("#fx-to").value) throw new Error("Choose two different currencies.");
      clearQuote();
      const data = await api("/exchange/quotes", { method: "POST", body: { fromCurrency: $("#fx-from").value, toCurrency: $("#fx-to").value, amount: validAmount("#fx-amount") } });
      renderQuote(data);
    });
    ["#fx-from", "#fx-to", "#fx-amount"].forEach(id => $(id).addEventListener("input", clearQuote));
    $("#fx-confirm").addEventListener("click", confirmExchange);
    $("#fx-refresh").addEventListener("click", () => busy($("#fx-refresh"), async () => {
      try { await Promise.all([refreshRates(), loadWallets()]); } catch (e) { notify(e.message, true); }
    }));
    setInterval(() => {
      if (quote && new Date(quote.expiresAt).getTime() <= Date.now()) {
        $("#fx-expiry").textContent = "This quote expired. Request a fresh quote before confirming.";
        $("#fx-confirm").disabled = true;
      }
    }, 1000);
    bind("#partner-link-form", "#partner-link-error", async () => {
      await api("/partners/connections", { method: "POST", body: { bankCode: $("#partner-bank").value, demoConsent: $("#partner-consent").checked } });
      $("#partner-consent").checked = false; clearBankReview(); await loadPartners(); notify("Fictional partner connected. No real bank access was requested.");
    });
    bind("#partner-move-form", "#partner-move-error", reviewBank);
    ["#partner-connection", "#partner-direction", "#partner-amount"].forEach(id => $(id).addEventListener("input", clearBankReview));
    $("#partner-confirm").addEventListener("click", confirmBank);
    $("#partners-refresh").addEventListener("click", () => busy($("#partners-refresh"), async () => {
      try { clearBankReview(); await loadPartners(); } catch (e) { notify(e.message, true); }
    }));
    $("#entry-direction").addEventListener("change", setEntryCategories);
    $("#finance-month").addEventListener("change", () => { financePage = 0; loadFinance().catch(e => notify(e.message, true)); });
    $("#finance-refresh").addEventListener("click", () => busy($("#finance-refresh"), async () => {
      try { await loadFinance(); } catch (e) { notify(e.message, true); }
    }));
    bind("#finance-entry-form", "#entry-error", async () => {
      const day = $("#entry-date").value;
      await api("/finance/entries", { method: "POST", body: { direction: $("#entry-direction").value, category: $("#entry-category").value, amount: validAmount("#entry-amount"), description: $("#entry-description").value.trim(), occurredOn: day } });
      $("#entry-amount").value = ""; $("#entry-description").value = ""; $("#finance-month").value = day.slice(0, 7);
      financePage = 0; await loadFinance(); notify("Tracker entry saved. Bank funds were not moved.");
    });
    bind("#finance-budget-form", "#budget-error", async () => {
      await api("/finance/budgets", { method: "POST", body: { month: $("#finance-month").value, category: $("#budget-category").value, amount: validAmount("#budget-amount") } });
      await loadFinance(); notify("Monthly category budget saved.");
    });
    bind("#finance-goal-form", "#goal-error", async () => {
      await api("/finance/goals", { method: "POST", body: { name: $("#goal-name").value.trim(), target: validAmount("#goal-target") } });
      $("#finance-goal-form").reset(); await loadFinance(); notify("Savings goal created.");
    });
    bind("#finance-allocation-form", "#allocation-error", async () => {
      const goal = $("#allocation-goal").value;
      if (!goal) throw new Error("Choose a savings goal first.");
      await api("/finance/goals/" + goal + "/allocations", { method: "POST", body: { amount: validAmount("#allocation-amount") } });
      $("#allocation-amount").value = ""; await loadFinance(); notify("Planning allocation recorded. No bank transfer was made.");
    });
    $("#finance-previous").addEventListener("click", () => { if (financePage > 0) financePage--; loadFinance().catch(e => notify(e.message, true)); });
    $("#finance-next").addEventListener("click", () => { financePage++; loadFinance().catch(e => notify(e.message, true)); });
  }
  async function open(view) {
    if (view === "exchange") return openExchange();
    if (view === "partners") { $("#partner-connections").replaceChildren(node("p", "Loading demo connections…", "muted")); return loadPartners(); }
    if (view === "finance") {
      $("#finance-totals").replaceChildren(node("p", "Loading your recorded finances…", "muted"));
      return loadFinance();
    }
  }
  function reset() {
    generation++; financeRequest++; clearQuote(); clearBankReview(); categories = null; connections = []; financePage = 0;
    ["#fx-wallets", "#partner-connections", "#finance-totals", "#finance-categories", "#finance-insights", "#finance-entries", "#finance-goals"].forEach(id => $(id).replaceChildren());
    document.querySelectorAll("#exchange-view form,#partners-view form,#finance-view form").forEach(form => form.reset());
    $("#finance-month").value = today().slice(0, 7); $("#entry-date").value = today(); $("#entry-date").max = today();
  }
  return { init, open, reset };
}
