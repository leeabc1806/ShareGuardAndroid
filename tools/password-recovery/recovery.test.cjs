// Isolated tests: every network response is simulated; no accounts are changed.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");

const code = fs.readFileSync(path.join(__dirname, "recovery.js"), "utf8");
const config = { supabaseUrl: "https://test-project.supabase.co", publishableKey: "sb_publishable_fixture" };

async function openPage(fragment = "", replies = []) {
  const elements = {};
  for (const id of ["status", "recovery-form", "instructions", "account", "password", "confirmation", "submit"]) {
    elements[id] = {
      hidden: ["recovery-form", "instructions", "account"].includes(id),
      disabled: false, value: "", textContent: "", dataset: {},
      focus() {},
      reset() { elements.password.value = ""; elements.confirmation.value = ""; },
      addEventListener(name, handler) { this[name] = handler; }
    };
  }
  const requests = [];
  const history = [];
  await vm.runInNewContext(code, {
    document: { getElementById: id => elements[id] },
    location: { hash: fragment, pathname: "/" },
    history: { replaceState: (...args) => history.push(args) },
    URLSearchParams, AbortController, setTimeout, clearTimeout,
    fetch: async (url, options = {}) => {
      requests.push({ url, options });
      const reply = url === "/config.json" ? { status: 200, data: config } : replies.shift();
      assert.ok(reply, "Unexpected request: " + url);
      if (reply.error) throw new Error("simulated network error");
      return { ok: reply.status >= 200 && reply.status < 300, status: reply.status, json: async () => reply.data };
    }
  });
  return {
    elements, requests, history,
    submit: async (password, confirmation = password) => {
      elements.password.value = password;
      elements.confirmation.value = confirmation;
      await elements["recovery-form"].submit({ preventDefault() {} });
    }
  };
}

const recovery = "#type=recovery&access_token=fake-recovery-token&refresh_token=unused-refresh-token";
const user = { status: 200, data: { id: "test-user", email: "test@example.invalid" } };

test("missing, non-recovery and expired links cannot make authenticated requests", async () => {
  for (const fragment of ["", "#type=signup&access_token=fake", "#error_code=otp_expired&access_token=fake"]) {
    const page = await openPage(fragment);
    assert.equal(page.requests.length, 0);
    assert.equal(page.elements["recovery-form"].hidden, true);
    assert.equal(page.elements.instructions.hidden, false);
    assert.deepEqual(page.history, [[null, "", "/"]]);
  }
});

test("valid recovery checks the account without changing its password", async () => {
  const page = await openPage(recovery, [user]);
  assert.equal(page.elements.account.textContent, "test@example.invalid");
  assert.equal(page.elements["recovery-form"].hidden, false);
  assert.equal(page.requests.length, 2);
  assert.equal(page.requests[1].url, config.supabaseUrl + "/auth/v1/user");
  assert.equal(page.requests[1].options.method, "GET");
  assert.equal(page.requests[1].options.headers.Authorization, "Bearer fake-recovery-token");
  assert.deepEqual(page.history, [[null, "", "/"]]);
});

test("an invalid recovery credential cannot expose the change form", async () => {
  const page = await openPage(recovery, [{ status: 401, data: {} }]);
  assert.equal(page.elements["recovery-form"].hidden, true);
  assert.equal(page.elements.instructions.hidden, false);
});

test("short and mismatching passwords are rejected before any update request", async () => {
  const page = await openPage(recovery, [user]);
  await page.submit("short");
  await page.submit("Test-password-123", "different-password");
  assert.equal(page.requests.length, 2);
  assert.equal(page.elements.status.dataset.kind, "error");
});

test("user submission sends only the password and clears it on success", async () => {
  const page = await openPage(recovery, [user, user]);
  await page.submit(" Test-password-123 ");
  const update = page.requests[2];
  assert.equal(update.options.method, "PUT");
  assert.equal(update.url, config.supabaseUrl + "/auth/v1/user");
  assert.deepEqual(JSON.parse(update.options.body), { password: " Test-password-123 " });
  assert.equal(update.options.redirect, "error");
  assert.equal(page.elements.status.dataset.kind, "success");
  assert.equal(page.elements.password.value, "");
  assert.equal(page.elements["recovery-form"].hidden, true);
  await page.submit("Another-password-123");
  assert.equal(page.requests.length, 3, "A completed session must not submit again");
});

test("expired session stops further updates and requires a new email", async () => {
  const page = await openPage(recovery, [user, { status: 401, data: {} }]);
  await page.submit("Test-password-123");
  assert.equal(page.elements["recovery-form"].hidden, true);
  assert.equal(page.elements.instructions.hidden, false);
  assert.equal(page.elements.password.value, "");
  await page.submit("Test-password-456");
  assert.equal(page.requests.length, 3);
});

test("password policy rejection leaves the form available for correction", async () => {
  for (const errorCode of ["weak_password", "same_password"]) {
    const page = await openPage(recovery, [user, { status: 422, data: { error_code: errorCode } }]);
    await page.submit("Test-password-123");
    assert.equal(page.elements.status.dataset.kind, "error");
    assert.equal(page.elements["recovery-form"].hidden, false);
    assert.equal(page.elements.submit.disabled, false);
  }
});

test("network failure never reports a successful password change", async () => {
  const page = await openPage(recovery, [user, { error: true }]);
  await page.submit("Test-password-123");
  assert.equal(page.elements.status.dataset.kind, "error");
  assert.equal(page.elements.submit.disabled, false);
});
