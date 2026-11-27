export function createSavings({ api, notify, node, money, getUser, getAccount, onMoneyChanged }) {
  const $ = selector => document.querySelector(selector);
  let generation = 0, items = [], editing = null, pending = null, historyPage = 0;
  const cash = value => "฿" + money(value);
  function option(select, text, value) { const item = node("option", text); item.value = value; select.append(item); }
  function amount(value, optional = false) {
    value = value.trim(); if (optional && !value) return null;
    if (!/^[0-9]+([.][0-9]{1,2})?$/.test(value) || Number(value) <= 0 || Number(value) > 1000000)
      throw new Error("Use a positive THB amount up to 1,000,000 with at most two decimals.");
    const [whole, fraction = ""] = value.split(".");
    return whole.replace(/^0+(?=[0-9])/, "") + "." + fraction.padEnd(2, "0");
  }
  function error(message = "") { $("#savings-error").textContent = message; $("#savings-error").hidden = !message; }
  async function busy(button, work) {
    if (button.disabled) return;
    button.disabled = true; button.setAttribute("aria-busy", "true");
    try { await work(); } catch (failure) { error(failure.message); }
    finally { button.disabled = false; button.removeAttribute("aria-busy"); }
  }
  function clearReview() { $("#savings-review").hidden = true; }
  function resetAccount() {
    editing = null; $("#savings-account-form").reset(); $("#savings-status-field").hidden = true;
    $("#savings-account-save").textContent = "Create savings account"; $("#savings-account-cancel").hidden = true;
  }
  function editAccount(account) {
    editing = account.id; $("#savings-name").value = account.name; $("#savings-target").value = account.target || "";
    $("#savings-status").value = account.status; $("#savings-status-field").hidden = false;
    $("#savings-account-save").textContent = "Save account details"; $("#savings-account-cancel").hidden = false;
    $("#savings-account-form").scrollIntoView({ block: "center" }); $("#savings-name").focus();
  }
  function render() {
    $("#savings-main-balance").textContent = getAccount() ? cash(getAccount().balance) : "—";
    const target = $("#savings-accounts"); target.replaceChildren();
    if (!items.length) target.append(node("p", "No savings accounts yet. Create one with zero funds, then move simulated THB from your main account.", "empty-state"));
    const select = $("#savings-move-account"), history = $("#savings-history-account"), chosen = select.value, previous = history.value;
    select.replaceChildren(); history.replaceChildren(); option(select, "Choose an active savings account", ""); option(history, "Choose savings history", "");
    items.forEach(account => {
      option(history, account.name, account.id); if (account.status === "ACTIVE") option(select, account.name, account.id);
      const card = node("article", undefined, "connection-card savings-card");
      card.append(node("h3", account.name), node("strong", cash(account.balance), "connection-balance"), node("p", account.status === "ACTIVE" ? "Active · simulated THB" : "Paused · new movements blocked", "muted"));
      if (account.target) {
        const progress = node("progress"); progress.max = 100; progress.value = Math.min(100, Number(account.percent)); progress.setAttribute("aria-label", account.name + " target progress");
        card.append(progress, node("p", "Target " + cash(account.target) + " · " + account.percent + "% · " + cash(account.remaining) + " remaining", "field-help"));
      } else card.append(node("p", "No target set. This is your actual simulated savings balance.", "field-help"));
      const edit = node("button", "Edit details", "text-button"); edit.type = "button"; edit.addEventListener("click", () => editAccount(account));
      const move = node("button", "Move funds", "text-button"); move.type = "button"; move.disabled = account.status !== "ACTIVE";
      move.addEventListener("click", () => { select.value = account.id; clearReview(); $("#savings-move-form").scrollIntoView({ block: "center" }); $("#savings-amount").focus(); });
      const activity = node("button", "History", "text-button"); activity.type = "button";
      activity.addEventListener("click", () => { history.value = account.id; historyPage = 0; loadHistory().catch(failure => error(failure.message)); });
      card.append(edit, move, activity); target.append(card);
    });
    if (items.some(a => a.id === chosen && a.status === "ACTIVE")) select.value = chosen;
    if (items.some(a => a.id === previous)) history.value = previous;
  }
  async function load() {
    const ticket = generation; const accounts = await api("/savings/accounts");
    if (ticket !== generation || !getUser()) return;
    items = accounts; render();
  }
  async function loadHistory() {
    const id = $("#savings-history-account").value, target = $("#savings-history"); target.replaceChildren();
    if (!id) { target.append(node("p", "Choose an account to see its immutable movement history.", "empty-state")); $("#savings-page").textContent = "No account selected"; $("#savings-previous").disabled = true; $("#savings-next").disabled = true; return; }
    const ticket = generation; const result = await api("/savings/accounts/" + id + "/history?page=" + historyPage + "&size=10");
    if (ticket !== generation || !getUser() || $("#savings-history-account").value !== id) return;
    if (!result.content.length) target.append(node("p", "No savings movements yet.", "empty-state"));
    result.content.forEach(movement => {
      const row = node("article", undefined, "category-row");
      row.append(node("strong", (movement.direction === "IN" ? "+ " : "− ") + cash(movement.amount)),
        node("p", movement.direction === "IN" ? "Moved from main account" : "Moved back to main account"),
        node("small", new Date(movement.createdAt).toLocaleString() + " · " + movement.reference)); target.append(row);
    });
    $("#savings-page").textContent = result.totalPages ? "Page " + (result.page + 1) + " of " + result.totalPages : "No movements";
    $("#savings-previous").disabled = result.page === 0; $("#savings-next").disabled = result.page + 1 >= result.totalPages;
  }
  async function open() {
    error(); await onMoneyChanged(); await load(); await loadHistory();
  }
  function init() {
    $("#savings-account-cancel").addEventListener("click", resetAccount);
    $("#savings-refresh").addEventListener("click", () => busy($("#savings-refresh"), open));
    $("#savings-account-form").addEventListener("submit", event => { event.preventDefault(); error(); busy($("#savings-account-save"), async () => {
      const body = { name: $("#savings-name").value.trim(), target: amount($("#savings-target").value, true) };
      if (editing) body.status = $("#savings-status").value;
      await api("/savings/accounts" + (editing ? "/" + editing : ""), { method: editing ? "PUT" : "POST", body });
      resetAccount(); await load(); notify("Savings details saved. Creating or editing an account does not create money.");
    }); });
    $("#savings-move-form").addEventListener("submit", event => { event.preventDefault(); error();
      try {
        const body = { savingsId: $("#savings-move-account").value, direction: $("#savings-direction").value, amount: amount($("#savings-amount").value) };
        const account = items.find(a => a.id === body.savingsId && a.status === "ACTIVE"); if (!account) throw new Error("Choose an active savings account.");
        const fingerprint = JSON.stringify(body); if (pending?.fingerprint !== fingerprint) pending = { fingerprint, body, key: crypto.randomUUID() };
        $("#savings-review-text").textContent = cash(body.amount) + (body.direction === "IN" ? " from main account to " : " from " + account.name + " back to main account") + (body.direction === "IN" ? account.name : "") + ". Simulated THB only; no fee.";
        $("#savings-review").hidden = false; $("#savings-confirm").focus();
      } catch (failure) { error(failure.message); }
    });
    ["#savings-move-account", "#savings-direction", "#savings-amount"].forEach(id => $(id).addEventListener("input", clearReview));
    $("#savings-confirm").addEventListener("click", () => busy($("#savings-confirm"), async () => {
      if (!pending) return;
      try {
        const result = await api("/savings/movements", { method: "POST", body: pending.body, key: pending.key });
        const id = pending.body.savingsId; pending = null; clearReview(); $("#savings-amount").value = "";
        notify("Savings movement completed. Reference: " + result.reference);
        await onMoneyChanged(); await load(); $("#savings-history-account").value = id; historyPage = 0; await loadHistory();
      } catch (failure) {
        error(failure.status === 0 ? "Response unknown. Keep details unchanged to retry with the same key, or inspect history before starting another movement." : failure.message);
      }
    }));
    $("#savings-history-account").addEventListener("change", () => { historyPage = 0; loadHistory().catch(failure => error(failure.message)); });
    $("#savings-previous").addEventListener("click", () => { if (historyPage > 0) historyPage--; loadHistory().catch(failure => error(failure.message)); });
    $("#savings-next").addEventListener("click", () => { historyPage++; loadHistory().catch(failure => error(failure.message)); });
  }
  function reset() {
    generation++; items = []; pending = null; historyPage = 0; resetAccount(); clearReview(); error();
    $("#savings-accounts").replaceChildren(); $("#savings-history").replaceChildren(); $("#savings-move-form").reset();
    $("#savings-history-account").replaceChildren(); $("#savings-main-balance").textContent = "—";
  }
  return { init, open, reset };
}
