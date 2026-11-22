export function createVerification({ api, notify, getUser, onOpen, onComplete }) {
  const $ = selector => document.querySelector(selector);
  let token = null, initialLink = false, generation = 0;
  function capture() {
    const params = new URLSearchParams(location.hash.slice(1));
    if (!params.has("verify")) return false;
    token = params.get("verify"); initialLink = true;
    history.replaceState(null, "", location.pathname + location.search); return true;
  }
  capture();
  function close() { token = null; $("#verification-panel").hidden = true; $("#verification-error").hidden = true; $("#auth-form").hidden = false; }
  function reset() { $("#verification-panel").hidden = true; $("#verification-error").hidden = true; generation++; $("#email-verification-status").textContent = "Loading email confirmation status…"; $("#email-verification-request").disabled = true; }
  function show() { $("#auth-form").hidden = true; $("#recovery-panel").hidden = true; $("#mfa-challenge-panel").hidden = true; $("#verification-panel").hidden = false; $("#verification-confirm").focus(); }
  async function open() {
    reset(); const current = generation;
    try {
      const status = await api("/auth/security/email");
      if (current !== generation || !getUser()) return;
      $("#email-verification-status").textContent = status.verified ? "Email confirmed for this NileBank profile." : status.available ? "Email not confirmed yet. Request a link for your profile address." : "Email confirmation delivery is disabled in this environment.";
      $("#email-verification-request").disabled = !status.available || status.verified;
    } catch (error) { if (current === generation) notify(error.message, true); }
  }
  $("#email-verification-request").addEventListener("click", async () => {
    const button = $("#email-verification-request"); button.disabled = true;
    try { const result = await api("/auth/security/email/request", { method: "POST" }); notify(result.message); }
    catch (error) { notify(error.message, true); }
    finally { if (getUser()) await open(); }
  });
  $("#verification-confirm").addEventListener("click", async () => {
    const button = $("#verification-confirm"); button.disabled = true; button.setAttribute("aria-busy", "true"); $("#verification-error").hidden = true;
    try {
      if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error("Confirmation link is invalid. Request a new email from security settings.");
      await api("/auth/email/confirm", { method: "POST", body: { token } }); close(); onComplete(); notify("Email confirmed. Sign in normally; no account was linked and no money moved.");
    } catch (error) { $("#verification-error").textContent = error.message; $("#verification-error").hidden = false; }
    finally { button.disabled = false; button.removeAttribute("aria-busy"); }
  });
  $("#verification-cancel").addEventListener("click", () => { close(); onComplete(); });
  window.addEventListener("hashchange", () => { if (capture()) { onOpen(); show(); } });
  return { open, reset, close, hasLink: () => initialLink, show };
}
