export function createSecurity({ api, notify, node, onSignedOut }) {
  const $ = (selector) => document.querySelector(selector);
  let methods;
  let options;
  function reset() {
    methods = null;
    $("#security-password-form").reset();
    $("#security-google-password").value = "";
  }
  async function open() {
    reset();
    $("#security-methods").replaceChildren(node("p", "Loading sign-in methods…"));
    try {
      [methods, options] = await Promise.all([api("/auth/security"), api("/auth/options")]);
      $("#security-methods").replaceChildren(
        node("p", "Password: " + (methods.password ? "available" : "not configured")),
        node("p", "Google: " + (methods.google ? "linked" : "not linked")),
        node("p", methods.recoveryAvailable ? "Password recovery: email delivery is configured." : "Password recovery: unavailable for this profile or environment.", "muted"),
        node("p", "MFA is not available in this version.", "muted"),
      );
      $("#security-password-fields").disabled = !methods.password;
      $("#security-google-password").disabled = !methods.password;
      $("#security-link").disabled = !methods.password || methods.google || !options.googleEnabled;
      $("#security-unlink").disabled = !methods.password || !methods.google;
      $("#security-google-note").textContent = !methods.password
        ? "Your Google identity is your only sign-in method and cannot be removed."
        : !options.googleEnabled ? "Google Sign-In is disabled in this environment."
        : "Linking requires your current password and Google's verified callback. Matching emails never link accounts.";
    } catch (error) { notify(error.message, true); }
  }
  async function google(action) {
    const input = $("#security-google-password");
    if (!input.reportValidity()) return;
    if (action === "unlink" && !confirm("Remove Google Sign-In and sign out all sessions? You will need your password to sign in again.")) return;
    const buttons = [$("#security-link"), $("#security-unlink")];
    buttons.forEach((button) => { button.disabled = true; button.setAttribute("aria-busy", "true"); });
    try {
      const data = await api("/auth/security/google/" + action, { method: "POST", body: { currentPassword: input.value } });
      input.value = "";
      if (action === "unlink") { onSignedOut("Google was removed. Sign in again with your password."); return; }
      if (data.redirect !== "/oauth2/authorization/google") throw new Error("Unexpected sign-in destination");
      location.assign(data.redirect);
    } catch (error) { notify(error.message, true); await open(); }
    finally { input.value = ""; buttons.forEach((button) => button.removeAttribute("aria-busy")); }
  }
  function init() {
    $("#security-link").addEventListener("click", () => google("link"));
    $("#security-unlink").addEventListener("click", () => google("unlink"));
    $("#security-password-form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const form = event.currentTarget;
      if (!form.reportValidity()) return;
      if ($("#security-new-password").value !== $("#security-confirm-password").value) { notify("New passwords must match.", true); return; }
      if (!confirm("Change your password and sign out all sessions?")) return;
      const button = $("#security-password-submit"); button.disabled = true; button.setAttribute("aria-busy", "true");
      try {
        await api("/auth/security/password", { method: "POST", body: { currentPassword: $("#security-old-password").value, newPassword: $("#security-new-password").value } });
        onSignedOut("Password changed. Sign in again with your new password.");
      } catch (error) { notify(error.message, true); }
      finally { form.reset(); button.disabled = false; button.removeAttribute("aria-busy"); }
    });
  }
  return { init, open, reset };
}
