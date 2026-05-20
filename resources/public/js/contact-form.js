(function () {
  var form = document.getElementById("contact-form");
  if (!form) return;
  var status = document.getElementById("contact-status");
  var submit = form.querySelector("button[type=submit]");

  var STRINGS = (document.documentElement.lang === "en")
    ? {sending: "Sending…",
       success: "Thanks — we'll get back to you shortly.",
       error: "Something went wrong. Please try again or email us directly."}
    : {sending: "Envoi…",
       success: "Merci — nous vous recontacterons rapidement.",
       error: "Une erreur est survenue. Réessayez ou écrivez-nous par email."};

  function showStatus(text, cls) {
    if (!status) return;
    status.textContent = text;
    status.className = cls;
  }

  async function postWithRetry(body, opts) {
    // Server rate-limits /contact at 2 req / 6 s per IP; cap attempts at 2.
    var retries = (opts && opts.retries) || 1;
    var timeoutMs = (opts && opts.timeoutMs) || 8000;
    for (var attempt = 0; attempt <= retries; attempt++) {
      var ctrl = new AbortController();
      var t = setTimeout(function () { ctrl.abort(); }, timeoutMs);
      try {
        var res = await fetch("/contact", {
          method: "POST",
          body: body,
          redirect: "manual",
          signal: ctrl.signal
        });
        clearTimeout(t);
        // 302 (opaqueredirect after backend success) or 2xx counts as success.
        if (res.type === "opaqueredirect" || res.ok) return res;
        // 4xx: do not retry, surface the error.
        if (res.status < 500) throw new Error("HTTP " + res.status);
        if (attempt === retries) throw new Error("HTTP " + res.status);
      } catch (e) {
        clearTimeout(t);
        if (attempt === retries) throw e;
      }
      await new Promise(function (r) { setTimeout(r, 300 * Math.pow(2, attempt)); });
    }
  }

  function setBusy(busy) {
    if (submit) submit.disabled = busy;
    form.classList.toggle("is-busy", busy);
  }

  function validate() {
    var invalid = form.querySelector(":invalid");
    if (invalid) {
      if (typeof invalid.reportValidity === "function") invalid.reportValidity();
      else invalid.focus();
      return false;
    }
    return true;
  }

  form.addEventListener("submit", async function (e) {
    e.preventDefault();
    if (!validate()) return;
    showStatus(STRINGS.sending, "w3-text-grey");
    setBusy(true);
    try {
      await postWithRetry(new FormData(form));
      form.reset();
      showStatus(STRINGS.success, "w3-text-green");
    } catch (err) {
      showStatus(STRINGS.error, "w3-text-red");
    } finally {
      setBusy(false);
    }
  });
})();
