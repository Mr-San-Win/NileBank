export function createPlanning({ api, notify, node, money, getUser }) {
  const $ = selector => document.querySelector(selector);
  let generation = 0, catalog = [], rules = [], editingCategory = null, editingRule = null;
  const today = () => {
    const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Bangkok", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
    return ["year", "month", "day"].map(type => parts.find(p => p.type === type).value).join("-");
  };
  function option(select, text, value) { const item = node("option", text); item.value = value; select.append(item); }
  function categoryOptions() {
    const select = $("#rule-category"), previous = select.value; select.replaceChildren();
    catalog.filter(c => !c.archived && c.direction === $("#rule-direction").value).forEach(c => option(select, c.label, c.code));
    if ([...select.options].some(o => o.value === previous)) select.value = previous;
  }
  function resetCategory() {
    editingCategory = null; $("#category-form").reset(); $("#category-direction").disabled = false;
    $("#category-save").textContent = "Create category"; $("#category-cancel").hidden = true;
  }
  function resetRule() {
    editingRule = null; $("#rule-form").reset(); $("#rule-start").value = today();
    $("#rule-start").disabled = false; $("#rule-frequency").disabled = false;
    $("#rule-save").textContent = "Create recurring entry"; $("#rule-cancel").hidden = true; categoryOptions();
  }
  async function action(button, work) {
    if (button.disabled) return;
    button.disabled = true; button.setAttribute("aria-busy", "true");
    try { await work(); } catch (error) { notify(error.message, true); }
    finally { button.disabled = false; button.removeAttribute("aria-busy"); }
  }
  function renderCategories() {
    const target = $("#planning-categories"); target.replaceChildren();
    catalog.forEach(category => {
      const card = node("article", undefined, "connection-card");
      card.append(node("h3", category.label), node("p", category.direction.toLowerCase() + (category.archived ? " · archived; history preserved" : ""), "muted"));
      if (!category.archived) {
        const rename = node("button", "Rename", "text-button"); rename.type = "button";
        rename.addEventListener("click", () => {
          editingCategory = category.code; $("#category-label").value = category.label; $("#category-direction").value = category.direction;
          $("#category-direction").disabled = true; $("#category-save").textContent = "Save category name"; $("#category-cancel").hidden = false;
          $("#category-label").focus(); $("#category-form").scrollIntoView({ block: "center" });
        });
        const archive = node("button", "Archive", "text-button"); archive.type = "button";
        archive.addEventListener("click", () => action(archive, async () => {
          if (!confirm("Archive this category? Existing records and budgets remain. Active recurring rules using it will pause.")) return;
          await api("/finance/category-management/" + encodeURIComponent(category.code), { method: "DELETE" });
          resetCategory(); await open(); notify("Category archived; historical records remain.");
        })); card.append(rename, archive);
      }
      target.append(card);
    });
  }
  function editRule(rule) {
    editingRule = rule.id; $("#rule-direction").value = rule.direction; categoryOptions(); $("#rule-category").value = rule.category;
    $("#rule-amount").value = rule.amount; $("#rule-description").value = rule.description;
    $("#rule-frequency").value = rule.frequency; $("#rule-start").value = rule.startDate; $("#rule-end").value = rule.endDate || "";
    $("#rule-start").disabled = true; $("#rule-frequency").disabled = true; $("#rule-save").textContent = "Save future settings"; $("#rule-cancel").hidden = false;
    $("#rule-form").scrollIntoView({ block: "center" }); $("#rule-amount").focus();
  }
  function renderRules() {
    const target = $("#planning-rules"); target.replaceChildren();
    if (!rules.length) { target.append(node("p", "No recurring entries yet. Create a schedule below; no bank funds will move.", "empty-state")); return; }
    rules.forEach(rule => {
      const card = node("article", undefined, "connection-card");
      const category = catalog.find(c => c.code === rule.category);
      card.append(node("h3", rule.description || category?.label || "Recurring entry"), node("strong", "฿" + money(rule.amount), "connection-balance"),
        node("p", rule.direction.toLowerCase() + " · " + (category?.label || rule.category) + " · " + rule.frequency.toLowerCase()),
        node("p", rule.status + " · Starts " + rule.startDate + (rule.endDate ? " · Ends " + rule.endDate : ""), "muted"),
        node("p", rule.status === "ACTIVE" ? "Next scheduled date: " + rule.nextOccurrence : "No generation while " + rule.status.toLowerCase(), "field-help"));
      if (rule.status !== "CANCELLED") {
        const edit = node("button", "Edit future settings", "text-button"); edit.type = "button"; edit.addEventListener("click", () => editRule(rule)); card.append(edit);
        const actions = rule.status === "ACTIVE" ? [["Pause", "PAUSE"], ["Cancel schedule", "CANCEL"]] : [["Resume", "RESUME"], ["Cancel schedule", "CANCEL"]];
        actions.forEach(([text, state]) => {
          const button = node("button", text, "text-button"); button.type = "button";
          button.addEventListener("click", () => action(button, async () => {
            if (state === "CANCEL" && !confirm("Cancel future occurrences? Existing tracker entries remain. A cancelled schedule cannot be resumed.")) return;
            await api("/finance/recurring/" + rule.id + "/state", { method: "POST", body: { action: state } });
            await open(); notify("Recurring schedule updated.");
          })); card.append(button);
        });
      }
      target.append(card);
    });
  }
  async function open() {
    if (!getUser()) return;
    const ticket = generation; $("#planning-status").textContent = "Loading categories and schedules…";
    try {
      const [categories, recurring] = await Promise.all([api("/finance/category-management"), api("/finance/recurring")]);
      if (ticket !== generation || !getUser()) return;
      catalog = categories; rules = recurring; categoryOptions(); renderCategories(); renderRules();
      $("#planning-status").textContent = "Tracker schedules use Asia/Bangkok dates. They never move simulated bank funds.";
    } catch (error) { if (ticket === generation) { $("#planning-status").textContent = error.message; notify(error.message, true); } }
  }
  function init() {
    $("#rule-start").value = today(); $("#rule-direction").addEventListener("change", categoryOptions);
    $("#category-cancel").addEventListener("click", resetCategory); $("#rule-cancel").addEventListener("click", resetRule);
    $("#planning-refresh").addEventListener("click", () => action($("#planning-refresh"), async () => {
      const result = await api("/finance/recurring/materialize", { method: "POST" }); await open(); notify(result.created + " due tracker entries created; no bank funds moved.");
    }));
    $("#category-form").addEventListener("submit", event => { event.preventDefault(); action($("#category-save"), async () => {
      const body = { label: $("#category-label").value.trim() }; if (!editingCategory) body.direction = $("#category-direction").value;
      await api("/finance/category-management" + (editingCategory ? "/" + encodeURIComponent(editingCategory) : ""), { method: editingCategory ? "PUT" : "POST", body });
      resetCategory(); await open(); notify("Category saved.");
    }); });
    $("#rule-form").addEventListener("submit", event => { event.preventDefault(); action($("#rule-save"), async () => {
      const amount = $("#rule-amount").value.trim();
      if (!/^[0-9]+([.][0-9]{1,2})?$/.test(amount) || Number(amount) <= 0 || Number(amount) > 1000000) throw new Error("Use a positive THB amount up to 1,000,000 with at most two decimals.");
      const body = { direction: $("#rule-direction").value, category: $("#rule-category").value, amount, description: $("#rule-description").value.trim(), endDate: $("#rule-end").value || null };
      if (!editingRule) { body.frequency = $("#rule-frequency").value; body.startDate = $("#rule-start").value; }
      await api("/finance/recurring" + (editingRule ? "/" + editingRule : ""), { method: editingRule ? "PUT" : "POST", body });
      resetRule(); await api("/finance/recurring/materialize", { method: "POST" }); await open(); notify("Schedule saved. Only tracker entries are generated.");
    }); });
  }
  function reset() {
    generation++; catalog = []; rules = []; resetCategory(); resetRule();
    $("#planning-categories").replaceChildren(); $("#planning-rules").replaceChildren(); $("#planning-status").textContent = "";
  }
  return { init, open, reset };
}
