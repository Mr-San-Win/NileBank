export function createRecovery({ api, notify, onReset, onOpenReset }) {
  const $ = selector => document.querySelector(selector);
  let token = null;
  let resetRequested = false;
  function captureResetLink() {
    const fragment = new URLSearchParams(location.hash.slice(1));
    if (!fragment.has("reset")) return false;
    token = fragment.get("reset"); resetRequested = true;
    history.replaceState(null, "", location.pathname + location.search);
    return true;
  }
  captureResetLink();
  async function busy(button, work) {
    button.disabled = true; button.setAttribute("aria-busy", "true");
    $("#recovery-error").hidden = true;
    try { await work(); }
    catch (failure) { $("#recovery-error").textContent = failure.message; $("#recovery-error").hidden = false; }
    finally { button.disabled = false; button.removeAttribute("aria-busy"); }
  }
  function close() {
    $("#recovery-panel").hidden = true;
    $("#auth-form").hidden = false;
    $("#recovery-form").reset(); $("#reset-form").reset();
    $("#recovery-error").hidden = true; token = null;
  }
  function open(reset = false) {
    $("#auth-form").hidden = true; $("#recovery-panel").hidden = false;
    $("#recovery-form").hidden = reset; $("#reset-form").hidden = !reset;
    $("#recovery-heading").textContent = reset ? "Choose a new demo password" : "Recover your demo profile";
    $(reset ? "#reset-password" : "#recovery-email").focus();
  }
  $("#forgot-password").addEventListener("click", () => { onOpenReset(); open(false); });
  $("#recovery-cancel").addEventListener("click", close);
  $("#recovery-form").addEventListener("submit", event => {
    event.preventDefault();
    busy($("#recovery-submit"), async () => {
      const result = await api("/auth/recovery/request", { method: "POST", body: { email: $("#recovery-email").value.trim() } });
      notify(result.message); $("#recovery-email").value = "";
    });
  });
  $("#reset-form").addEventListener("submit", event => {
    event.preventDefault();
    busy($("#reset-submit"), async () => {
      if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error("Reset link is invalid. Request a new email.");
      const password = $("#reset-password").value;
      if (password !== $("#reset-confirm").value) throw new Error("The passwords do not match.");
      await api("/auth/recovery/reset", { method: "POST", body: { token, password } });
      close(); onReset(); notify("Password reset. Sign in with your new password; previous sessions have ended.");
    });
  });
  window.addEventListener("hashchange", () => {
    if (captureResetLink()) { onOpenReset(); open(true); }
  });
  return {
    configure(enabled) { $("#forgot-password").hidden = !enabled; },
    hasResetLink() { return resetRequested; },
    openReset() { open(true); },
    close,
  };
}
