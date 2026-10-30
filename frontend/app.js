import { createExtensions } from "./extensions.js";
const $ = (selector) => document.querySelector(selector);
const state = {
  user: null,
  account: null,
  csrf: null,
  mode: "login",
  view: "overview",
  page: 0,
  moneyMode: "deposit",
  pending: null,
};
let noticeTimer;
const extensions=createExtensions({api,notify,money,node,getUser:()=>state.user,getAccount:()=>state.account,onMoneyChanged:async()=>{await loadAccount();if(state.view==="overview")await loadHistory(true);}});

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

async function refreshCsrf() {
  const response = await fetch("/api/v1/auth/csrf", {
    credentials: "same-origin",
    cache: "no-store",
  });
  if (!response.ok)
    throw new ApiError(
      "Could not prepare a secure session. Please refresh.",
      response.status,
    );
  state.csrf = await response.json();
}

async function api(path, { method = "GET", body, key } = {}) {
  if (method !== "GET" && !state.csrf) await refreshCsrf();
  const headers = { Accept: "application/json" };
  if (body) headers["Content-Type"] = "application/json";
  if (method !== "GET") headers[state.csrf.headerName] = state.csrf.token;
  if (key) headers["Idempotency-Key"] = key;
  let response;
  try {
    response = await fetch("/api/v1" + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(20000),
    });
  } catch {
    throw new ApiError(
      method === "GET"
        ? "Could not connect. Check your connection and try again."
        : "The response was lost. Check your activity before repeating a deposit or withdrawal. A transfer can be retried safely with the same details.",
      0,
    );
  }
  const data =
    response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && state.user)
      showAuth("Your session ended. Please sign in again.");
    if (response.status === 403) state.csrf = null;
    const errors = data?.errors
      ? Object.entries(data.errors)
          .map(([field, message]) => field + ": " + message)
          .join("; ")
      : "";
    throw new ApiError(
      errors || data?.message || "The request could not be completed.",
      response.status,
    );
  }
  return data;
}

function notify(message, error = false) {
  clearTimeout(noticeTimer);
  const notice = $("#notice");
  notice.textContent = message;
  notice.classList.toggle("error", error);
  notice.hidden = false;
  noticeTimer = setTimeout(
    () => {
      notice.hidden = true;
    },
    error ? 12000 : 6500,
  );
}

function setError(selector, message = "") {
  const target = $(selector);
  target.textContent = message;
  target.hidden = !message;
}

async function busy(button, action) {
  if (button.disabled) return;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "Please wait…";
  try {
    await action();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

// Format decimal strings for display without doing floating-point money arithmetic.
function money(value) {
  const [whole, fraction = ""] = String(value ?? "0").split(".");
  return (
    whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") +
    "." +
    fraction.padEnd(2, "0").slice(0, 2)
  );
}
function date(value) {
  return new Intl.DateTimeFormat("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(value));
}
function amount(input) {
  const value = input.value.trim();
  if (
    !/^[0-9]+(\.[0-9]{1,2})?$/.test(value) ||
    Number(value) < 0.01 ||
    Number(value) > 1000000
  ) {
    throw new ApiError(
      "Enter an amount from ฿0.01 to ฿1,000,000.00 with at most 2 decimal places.",
      400,
    );
  }
  return value;
}
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function authMode(mode) {
  state.mode = mode;
  const register = mode === "register";
  $("#name-field").hidden = !register;
  $("#full-name").required = register;
  $("#password").minLength = register ? 12 : 1;
  $("#password").autocomplete = register ? "new-password" : "current-password";
  $("#password-help").hidden = !register;
  $("#auth-heading").textContent = register
    ? "Your next step starts here."
    : "Welcome back.";
  $("#auth-description").textContent = register
    ? "Create a profile to explore simulated banking."
    : "Sign in to your personal demo account.";
  $("#auth-submit").textContent = register
    ? "Create demo profile ↗"
    : "Sign in to NileBank ↗";
  $("#login-tab").classList.toggle("selected", !register);
  $("#register-tab").classList.toggle("selected", register);
  $("#login-tab").setAttribute("aria-pressed", String(!register));
  $("#register-tab").setAttribute("aria-pressed", String(register));
  setError("#auth-error");
}
function showAuth(message = "") {
  clearTimeout(noticeTimer);
  $("#notice").hidden = true;
  state.user = null;
  state.account = null;
  state.pending = null;
  state.csrf = null;
  extensions.reset();
  document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
  $("#bank-view").hidden = true;
  $("#auth-view").hidden = false;
  $("#auth-form").reset();
  authMode("login");
  if (message) notify(message, true);
}
function updateProfile() {
  const details = $("#profile-details");
  details.replaceChildren();
  const rows = [
    ["Full name", state.user.fullName],
    ["Email address", state.user.email],
    ["Member since", date(state.user.createdAt)],
    [
      "Account number",
      state.account?.accountNumber || "No account created yet",
    ],
    ["Currency", "THB · simulated money"],
    ["Account status", state.account?.status || "Not created"],
  ];
  rows.forEach(([label, value]) =>
    details.append(node("dt", label), node("dd", value)),
  );
}
function renderAccount() {
  $("#no-account").hidden = !!state.account;
  $("#account-dashboard").hidden = !state.account;
  if (state.account) {
    $("#balance").textContent = money(state.account.balance);
    $("#account-number").textContent = state.account.accountNumber;
    $("#account-status").textContent = state.account.status;
  }
  updateProfile();
}
async function loadAccount() {
  try {
    state.account = await api("/accounts/me");
  } catch (error) {
    if (error.status === 404) state.account = null;
    else throw error;
  }
  renderAccount();
}
async function showBank() {
  clearTimeout(noticeTimer);
  $("#notice").hidden = true;
  $("#auth-view").hidden = true;
  $("#bank-view").hidden = false;
  $("#user-name").textContent = state.user.fullName;
  $("#avatar").textContent = state.user.fullName
    .split(/\s+/)
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();
  $("#greeting").textContent =
    "Welcome back, " + state.user.fullName.split(/\s+/)[0] + ".";
  await loadAccount();
  await navigate("overview");
}
async function navigate(view) {
  if (["transfer","exchange","partners"].includes(view) && !state.account) {
    notify("Create your virtual account first.", true);
    view = "overview";
  }
  state.view = view;
  document.querySelectorAll(".view").forEach((section) => {
    section.hidden = section.id !== view + "-view";
  });
  document.querySelectorAll(".nav-item[data-view]").forEach((button) => {
    button.classList.toggle("active", button.dataset.view === view);
    if (button.dataset.view === view)
      button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  $("#breadcrumb-current").textContent = {
    overview: "Overview",
    transfer: "Transfer money",
    history: "Transactions",
    profile: "Account details",
    exchange: "Currency exchange",
    partners: "Demo banks",
    finance: "Finance tracker",
  }[view];
  if (view === "overview" && state.account) await loadHistory(true);
  if (view === "history") {
    state.page = 0;
    await loadHistory();
  }
  if (view === "profile") updateProfile();
  if (["exchange","partners","finance"].includes(view)) await extensions.open(view);
}
function renderHistory(target, transactions) {
  target.replaceChildren();
  if (!transactions.length) {
    const empty = node("div", undefined, "empty-state");
    empty.append(
      node("span", "◇", "large-symbol"),
      node("strong", "A fresh start."),
      node("span", "Your successful transactions will appear here."),
    );
    target.append(empty);
    return;
  }
  const wrapper = node("div", undefined, "table-scroll");
  const table = node("table", undefined, "transaction-table");
  const head = node("thead");
  const header = node("tr");
  ["TRANSACTION", "DATE", "REFERENCE", "STATUS", "AMOUNT"].forEach(
    (name, index) => {
      header.append(
        node(
          "th",
          name,
          index === 2 ? "reference-col" : index === 3 ? "status-col" : "",
        ),
      );
    },
  );
  head.append(header);
  table.append(head);
  const body = node("tbody");
  transactions.forEach((transaction) => {
    const incoming = transaction.direction === "IN";
    const row = node("tr");
    const titleCell = node("td");
    const title = node("div", undefined, "transaction-name");
    const labels = {
      DEPOSIT: "Simulated deposit",
      WITHDRAWAL: "Withdrawal",
      TRANSFER: incoming ? "Transfer received" : "Transfer sent",
      EXCHANGE: "Currency exchange",
      BANK_IN: "Demo bank top-up",
      BANK_OUT: "Demo bank transfer",
    };
    title.append(
      node(
        "span",
        ["TRANSFER","EXCHANGE"].includes(transaction.type) ? "⇄" : incoming ? "↓" : "↑",
        "transaction-symbol" + (incoming ? "" : " out"),
      ),
    );
    const description = node("div");
    description.append(
      node("strong", labels[transaction.type]),
      node(
        "small",
        transaction.type === "TRANSFER"
          ? incoming
            ? "From " + transaction.sourceAccount
            : "To " + transaction.destinationAccount
          : transaction.type === "EXCHANGE" ? "Received "+transaction.counterAmount+" "+transaction.counterCurrency : transaction.description || "Virtual THB account",
      ),
    );
    title.append(description);
    titleCell.append(title);
    row.append(titleCell, node("td", date(transaction.createdAt)));
    const reference = node(
      "td",
      transaction.reference.slice(0, 11) + "…",
      "reference-col",
    );
    reference.title = transaction.reference;
    row.append(reference);
    const status = node("td", undefined, "status-col");
    status.append(node("span", "Completed", "status-badge"));
    row.append(status);
    row.append(
      node(
        "td",
        (incoming ? "+" : "−") + " " + ({THB:"฿",USD:"$",EUR:"€"}[transaction.currency || "THB"]) + money(transaction.amount),
        "amount" + (incoming ? " in" : ""),
      ),
    );
    body.append(row);
  });
  table.append(body);
  wrapper.append(table);
  target.append(wrapper);
}
async function loadHistory(recent = false) {
  const target = recent ? $("#recent-transactions") : $("#all-transactions");
  if (!state.account) {
    renderHistory(target, []);
    $("#page-label").textContent = "No transactions";
    $("#previous-page").disabled = true;
    $("#next-page").disabled = true;
    return;
  }
  target.replaceChildren(node("div", "Loading transactions…", "empty-state"));
  try {
    const data = await api(
      "/transactions?page=" +
        (recent ? 0 : state.page) +
        "&size=" +
        (recent ? 5 : 10),
    );
    renderHistory(target, data.content);
    if (!recent) {
      $("#page-label").textContent = data.totalPages
        ? "Page " + (data.page + 1) + " of " + data.totalPages
        : "No transactions";
      $("#previous-page").disabled = data.page === 0;
      $("#next-page").disabled = data.page + 1 >= data.totalPages;
    }
  } catch (error) {
    target.replaceChildren(node("div", error.message, "empty-state"));
    throw error;
  }
}
async function refreshAfterSuccess(message) {
  notify(message);
  try {
    await loadAccount();
    if (state.view === "overview") await loadHistory(true);
  } catch {
    notify(
      message +
        " The balance refresh failed; reload to see the latest activity.",
      true,
    );
  }
}
function openMoney(mode) {
  if (!state.account) {
    notify("Create your virtual account first.", true);
    return;
  }
  state.moneyMode = mode;
  const deposit = mode === "deposit";
  $("#money-heading").textContent = deposit
    ? "Add a little possibility."
    : "Withdraw demo funds.";
  $("#money-description").textContent = deposit
    ? "Top up your virtual account with simulated THB."
    : "Take simulated THB out of your virtual account.";
  $("#money-submit").textContent = deposit
    ? "Confirm simulated deposit ↓"
    : "Confirm withdrawal ↑";
  $("#money-form").reset();
  setError("#money-error");
  $("#money-dialog").showModal();
  $("#money-amount").focus();
}

$("#login-tab").addEventListener("click", () => authMode("login"));
$("#register-tab").addEventListener("click", () => authMode("register"));
$("#auth-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  setError("#auth-error");
  await busy($("#auth-submit"), async () => {
    try {
      const body = {
        email: $("#email").value.trim(),
        password: $("#password").value,
      };
      if (state.mode === "register") {
        await api("/auth/register", {
          method: "POST",
          body: { ...body, fullName: $("#full-name").value.trim() },
        });
        authMode("login");
        notify("Profile created. Sign in to open your virtual account.");
        $("#password").value = "";
        $("#password").focus();
      } else {
        state.user = await api("/auth/login", { method: "POST", body });
        $("#password").value = "";
        await refreshCsrf();
        await showBank();
      }
    } catch (error) {
      if (state.user) notify(error.message, true);
      else setError("#auth-error", error.message);
    }
  });
  // busy() restores its original label; align it with the current mode.
  $("#auth-submit").textContent =
    state.mode === "register"
      ? "Create demo profile ↗"
      : "Sign in to NileBank ↗";
});
$("#logout").addEventListener("click", async () => {
  await busy($("#logout"), async () => {
    try {
      await api("/auth/logout", { method: "POST" });
      showAuth();
      notify("You have signed out securely.");
    } catch (error) {
      notify(error.message, true);
    }
  });
});
$("#create-account").addEventListener("click", async () => {
  await busy($("#create-account"), async () => {
    try {
      await api("/accounts", { method: "POST" });
      await refreshAfterSuccess(
        "Your virtual account is ready. Add simulated funds to get started.",
      );
    } catch (error) {
      notify(error.message, true);
    }
  });
});
document
  .querySelectorAll("[data-view]")
  .forEach((button) =>
    button.addEventListener("click", () =>
      navigate(button.dataset.view).catch((error) =>
        notify(error.message, true),
      ),
    ),
  );
document
  .querySelectorAll("[data-action]")
  .forEach((button) =>
    button.addEventListener("click", () => openMoney(button.dataset.action)),
  );
$("#copy-number").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(state.account.accountNumber);
    notify("Account number copied.");
  } catch {
    notify(
      "Select and copy the account number shown on your balance card.",
      true,
    );
  }
});
$("#close-money").addEventListener("click", () => $("#money-dialog").close());
$("#money-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  setError("#money-error");
  await busy($("#money-submit"), async () => {
    try {
      await api("/transactions/" + state.moneyMode, {
        method: "POST",
        body: {
          accountId: state.account.id,
          amount: amount($("#money-amount")),
        },
      });
      $("#money-dialog").close();
      await refreshAfterSuccess(
        state.moneyMode === "deposit"
          ? "Simulated deposit completed."
          : "Withdrawal completed.",
      );
    } catch (error) {
      setError("#money-error", error.message);
    }
  });
});
$("#transfer-form").addEventListener("submit", (event) => {
  event.preventDefault();
  setError("#transfer-error");
  try {
    if (!state.account)
      throw new ApiError("Create your virtual account first.", 400);
    const body = {
      sourceAccountId: state.account.id,
      recipientAccountNumber: $("#recipient").value.trim().toUpperCase(),
      amount: amount($("#transfer-amount")),
    };
    if (body.recipientAccountNumber === state.account.accountNumber)
      throw new ApiError("Choose a different recipient account.", 400);
    const fingerprint = JSON.stringify(body);
    if (state.pending?.fingerprint !== fingerprint)
      state.pending = { body, fingerprint, key: crypto.randomUUID() };
    $("#confirm-recipient").textContent = body.recipientAccountNumber;
    $("#confirm-amount").textContent = "฿" + money(body.amount) + " THB";
    setError("#confirm-error");
    $("#confirm-dialog").showModal();
  } catch (error) {
    setError("#transfer-error", error.message);
  }
});
$("#close-confirm").addEventListener("click", () =>
  $("#confirm-dialog").close(),
);
$("#confirm-transfer").addEventListener("click", async () => {
  await busy($("#confirm-transfer"), async () => {
    setError("#confirm-error");
    try {
      const result = await api("/transfers", {
        method: "POST",
        body: state.pending.body,
        key: state.pending.key,
      });
      state.pending = null;
      $("#transfer-form").reset();
      $("#confirm-dialog").close();
      await refreshAfterSuccess(
        "Transfer completed. Reference: " + result.reference,
      );
      await navigate("overview");
    } catch (error) {
      setError("#confirm-error", error.message);
    }
  });
});
$("#refresh-history").addEventListener("click", () =>
  loadHistory().catch((error) => notify(error.message, true)),
);
$("#previous-page").addEventListener("click", async () => {
  if (state.page > 0) state.page--;
  try {
    await loadHistory();
  } catch (error) {
    notify(error.message, true);
  }
});
$("#next-page").addEventListener("click", async () => {
  state.page++;
  try {
    await loadHistory();
  } catch (error) {
    notify(error.message, true);
  }
});

async function init() {
  authMode("login");
  try {
    await refreshCsrf();
    try {
      state.user = await api("/auth/me");
    } catch (error) {
      if (error.status === 401) return;
      throw error;
    }
    await showBank();
  } catch (error) {
    notify(error.message, true);
  }
}
extensions.init();
init();
