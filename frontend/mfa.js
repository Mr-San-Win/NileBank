export function createMfa({ api, notify, node, getUser, onSignedOut, onAuthenticated }) {
  const $ = selector => document.querySelector(selector);
  let generation = 0, expiryTimer, passwordRequired = true;
  function reset() {
    generation++; clearTimeout(expiryTimer); passwordRequired = true;
    $("#mfa-google-reauth").hidden = true; $("#mfa-google-note").hidden = true;
    $("#mfa-challenge-form").reset(); $("#mfa-settings-form").reset();
    $("#mfa-fields").disabled = true; $("#mfa-status").textContent = "Loading authenticator settings…";
    $("#mfa-challenge-panel").hidden = true;
    $("#auth-form").hidden = false;
    $("#mfa-setup").hidden = true; $("#mfa-secret").textContent = ""; $("#mfa-setup-details").textContent = "";
    $("#mfa-codes").replaceChildren();
    $("#mfa-error").hidden = true; $("#mfa-challenge-error").hidden = true;
  }
  async function busy(button, target, work) {
    button.disabled = true; button.setAttribute("aria-busy", "true"); $(target).hidden = true;
    try { await work(); }
    catch (error) { $(target).textContent = error.message; $(target).hidden = false; }
    finally { button.disabled = false; button.removeAttribute("aria-busy"); }
  }
  function challenge() {
    $("#auth-form").hidden = true; $("#recovery-panel").hidden = true;
    $("#mfa-challenge-panel").hidden = false; $("#mfa-login-code").focus();
  }
  async function open() {
    const ticket = ++generation;
    $("#mfa-fields").disabled = true;
    $("#mfa-status").textContent = "Loading authenticator settings…";
    $("#mfa-settings-form").reset(); $("#mfa-secret").textContent = ""; $("#mfa-setup-details").textContent = ""; $("#mfa-setup").hidden = true;
    try {
      const settings = await api("/auth/security/mfa");
      if (ticket !== generation || !getUser()) return;
      $("#mfa-status").textContent = settings.enabled
        ? "Authenticator enabled · " + settings.recoveryCodesRemaining + " recovery codes remaining."
        : settings.available ? "Authenticator not enabled. You can enroll a TOTP app below." : "Enrollment is unavailable for this profile or environment. Existing enrolled users still require their factor.";
      passwordRequired = settings.passwordRequired;
      $("#mfa-current-password").disabled = !passwordRequired;
      $("#mfa-current-password").required = passwordRequired;
      $("#mfa-current-password").hidden = !passwordRequired;
      $("#mfa-current-password-label").hidden = !passwordRequired;
      $("#mfa-google-reauth").hidden = passwordRequired || !(settings.available || settings.enabled);
      $("#mfa-google-note").hidden = passwordRequired;
      const needsGoogle = !passwordRequired && !settings.googleVerified;
      $("#mfa-fields").disabled = !(settings.enabled || settings.available);
      $("#mfa-enroll").disabled = settings.enabled || !settings.available || needsGoogle;
      $("#mfa-disable").disabled = !settings.enabled || needsGoogle;
      $("#mfa-regenerate").disabled = !settings.enabled || needsGoogle;
    } catch (error) { notify(error.message, true); }
  }
  function codes(values) {
    onSignedOut("Authenticator settings changed. Sign in again; save these recovery codes offline.");
    $("#mfa-codes").replaceChildren(...values.map(value => node("code", value)));
    $("#mfa-codes-dialog").showModal();
  }
  async function manage(action) {
    const password = $("#mfa-current-password");
    if (passwordRequired && !password.reportValidity()) return;
    if (["disable", "recovery-codes"].includes(action) && !confirm("Change authenticator protection and sign out all sessions?")) return;
    const button = $(action === "enroll" ? "#mfa-enroll" : action === "confirm" ? "#mfa-confirm" : action === "disable" ? "#mfa-disable" : "#mfa-regenerate");
    const ticket = generation;
    $("#mfa-fields").disabled = true;
    await busy(button, "#mfa-error", async () => {
      const result = await api("/auth/security/mfa/" + action, { method: "POST", body: { currentPassword: passwordRequired ? password.value : null, code: $("#mfa-settings-code").value || null } });
      if (action === "enroll") {
        $("#mfa-secret").textContent = result.secret; $("#mfa-setup").hidden = false;
        $("#mfa-setup-details").textContent = result.issuer + " · " + result.account + " · SHA-1 · 6 digits · 30 seconds. Re-enter your password and the authenticator code to confirm. Setup expires in 10 minutes.";
        clearTimeout(expiryTimer); expiryTimer = setTimeout(() => { $("#mfa-secret").textContent = ""; $("#mfa-setup-details").textContent = ""; $("#mfa-setup").hidden = true; }, Math.max(0, new Date(result.expiresAt).getTime() - Date.now()));
        $("#mfa-settings-code").focus();
      } else if (result?.recoveryCodes) codes(result.recoveryCodes);
      else onSignedOut("Authenticator disabled. Sign in again with your remaining sign-in method.");
    });
    password.value = ""; $("#mfa-settings-code").value = "";
    if (ticket === generation && getUser()) $("#mfa-fields").disabled = false;
  }
  $("#mfa-challenge-form").addEventListener("submit", event => {
    event.preventDefault();
    busy($("#mfa-login-submit"), "#mfa-challenge-error", async () => {
      const user = await api("/auth/mfa/challenge", { method: "POST", body: { code: $("#mfa-login-code").value.trim() } });
      reset(); await onAuthenticated(user);
    });
  });
  $("#mfa-login-cancel").addEventListener("click", async () => {
    try { await api("/auth/logout", { method: "POST" }); } catch { /* Pending auth never grants bank access. */ }
    onSignedOut("Sign-in cancelled.");
  });
  $("#mfa-settings-form").addEventListener("submit", event => event.preventDefault());
  $("#mfa-google-reauth").addEventListener("click", () => busy($("#mfa-google-reauth"), "#mfa-error", async () => {
    const result = await api("/auth/security/google/reauthenticate", { method: "POST" });
    if (result.redirect !== "/oauth2/authorization/google") throw new Error("Unexpected verification destination");
    location.assign(result.redirect);
  }));
  $("#mfa-enroll").addEventListener("click", () => manage("enroll"));
  $("#mfa-confirm").addEventListener("click", () => manage("confirm"));
  $("#mfa-disable").addEventListener("click", () => manage("disable"));
  $("#mfa-regenerate").addEventListener("click", () => manage("recovery-codes"));
  $("#mfa-codes-close").addEventListener("click", () => $("#mfa-codes-dialog").close());
  $("#mfa-codes-dialog").addEventListener("close", () => $("#mfa-codes").replaceChildren());
  return { reset, open, challenge };
}
