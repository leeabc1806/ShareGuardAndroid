"use strict";

(async () => {
  const status = document.getElementById("status");
  const form = document.getElementById("recovery-form");
  const instructions = document.getElementById("instructions");
  const account = document.getElementById("account");
  const password = document.getElementById("password");
  const confirmation = document.getElementById("confirmation");
  const submit = document.getElementById("submit");
  let params = new URLSearchParams(location.hash.slice(1));
  let accessToken = params.get("access_token") || "";
  const recoveryType = params.get("type");
  const callbackError = params.get("error") || params.get("error_code");
  // Keep the recovery credential in memory only, never in history or storage.
  history.replaceState(null, "", location.pathname);
  params = null;

  function show(message, kind = "info") {
    status.textContent = message;
    status.dataset.kind = kind;
  }

  function requireNewLink(message) {
    accessToken = "";
    form.reset();
    form.hidden = true;
    account.hidden = true;
    instructions.hidden = false;
    show(message, "error");
  }

  if (callbackError) {
    requireNewLink("메일 링크가 만료되었거나 이미 사용되었습니다. 새 재설정 메일을 받아주세요.");
    return;
  }
  if (!accessToken || recoveryType !== "recovery") {
    accessToken = "";
    instructions.hidden = false;
    show("복구 페이지가 준비되었습니다. 비밀번호 재설정 메일의 링크로 접속해주세요.");
    return;
  }

  let config;
  async function authRequest(method, body) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(config.supabaseUrl + "/auth/v1/user", {
        method,
        headers: {
          apikey: config.publishableKey,
          Authorization: "Bearer " + accessToken,
          "Content-Type": "application/json"
        },
        body: body ? JSON.stringify(body) : undefined,
        cache: "no-store",
        credentials: "omit",
        redirect: "error",
        referrerPolicy: "no-referrer",
        signal: controller.signal
      });
      const data = await response.json().catch(() => ({}));
      return { response, data };
    } finally {
      clearTimeout(timeout);
    }
  }

  try {
    const configResponse = await fetch("/config.json", { cache: "no-store" });
    if (!configResponse.ok) throw new Error("config");
    config = await configResponse.json();
    const { response, data } = await authRequest("GET");
    if (!response.ok || !data.id || !data.email) {
      requireNewLink("계정을 확인하지 못했습니다. 새 비밀번호 재설정 메일로 다시 시작해주세요.");
      return;
    }
    account.textContent = data.email;
    account.hidden = false;
    form.hidden = false;
    show("계정이 확인되었습니다. 새 비밀번호를 두 번 입력해주세요.");
    password.focus();
  } catch {
    requireNewLink("계정 서버에 연결하지 못했습니다. 인터넷 연결을 확인한 뒤 새 재설정 메일로 다시 시작해주세요.");
    return;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!accessToken || submit.disabled) return;
    if ([...password.value].length < 8) {
      show("비밀번호를 8자 이상 입력해주세요.", "error");
      password.focus();
      return;
    }
    if (password.value !== confirmation.value) {
      show("두 비밀번호가 일치하지 않습니다.", "error");
      confirmation.focus();
      return;
    }
    submit.disabled = true;
    password.disabled = true;
    confirmation.disabled = true;
    show("비밀번호를 변경하고 있습니다.");
    try {
      const { response, data } = await authRequest("PUT", { password: password.value });
      if (response.ok && data.id) {
        accessToken = "";
        form.reset();
        form.hidden = true;
        show("비밀번호가 변경되었습니다. 휴대폰의 ShareGuard 앱에서 이 이메일과 새 비밀번호로 로그인하세요.", "success");
      } else if (response.status === 401 || response.status === 403) {
        requireNewLink("인증이 만료되었거나 추가 인증이 필요합니다. 새 재설정 메일로 다시 시작해주세요.");
      } else if (data.code === "same_password" || data.error_code === "same_password") {
        show("기존 비밀번호와 다른 새 비밀번호를 입력해주세요.", "error");
      } else if (data.code === "weak_password" || data.error_code === "weak_password") {
        show("더 강한 비밀번호가 필요합니다. 길이를 늘리고 대문자·소문자·숫자·기호를 조합해주세요.", "error");
      } else {
        show("비밀번호를 변경하지 못했습니다. 잠시 후 다시 시도해주세요. (HTTP " + response.status + ")", "error");
      }
    } catch {
      show("변경 결과를 확인하지 못했습니다. 앱에서 새 비밀번호로 로그인해보고, 실패하면 다시 시도해주세요.", "error");
    } finally {
      submit.disabled = false;
      password.disabled = false;
      confirmation.disabled = false;
    }
  });
})();
