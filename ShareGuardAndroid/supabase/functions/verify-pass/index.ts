import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
};

function jsonResponse(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") {
    return jsonResponse(405, { message: "POST 요청만 허용됩니다." });
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const portOneApiSecret = Deno.env.get("PORTONE_API_SECRET");
  const authorization = request.headers.get("Authorization");
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return jsonResponse(500, { message: "Supabase 서버 환경 변수가 없습니다." });
  }
  if (!portOneApiSecret) {
    return jsonResponse(503, { message: "PORTONE_API_SECRET이 서버에 설정되지 않았습니다." });
  }
  if (!authorization?.startsWith("Bearer ")) {
    return jsonResponse(401, { message: "로그인이 필요합니다." });
  }

  const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
    headers: { apikey: anonKey, Authorization: authorization },
  });
  if (!userResponse.ok) {
    return jsonResponse(401, { message: "유효하지 않은 로그인 세션입니다." });
  }
  const user = await userResponse.json() as { id?: string };
  if (!user.id) {
    return jsonResponse(401, { message: "로그인 사용자를 확인할 수 없습니다." });
  }

  let identityVerificationId = "";
  try {
    const body = await request.json() as { identityVerificationId?: string };
    identityVerificationId = body.identityVerificationId?.trim() ?? "";
  } catch {
    return jsonResponse(400, { message: "요청 본문이 올바르지 않습니다." });
  }

  const expectedPrefix = `shareguard-${user.id}-`;
  if (!identityVerificationId.startsWith(expectedPrefix) || identityVerificationId.length > 160) {
    return jsonResponse(400, { message: "본인인증 요청 ID가 로그인 사용자와 일치하지 않습니다." });
  }

  const portOneResponse = await fetch(
    `https://api.portone.io/identity-verifications/${encodeURIComponent(identityVerificationId)}`,
    { headers: { Authorization: `PortOne ${portOneApiSecret}` } },
  );
  if (!portOneResponse.ok) {
    return jsonResponse(502, { message: "PortOne에서 본인인증 결과를 조회하지 못했습니다." });
  }
  const verification = await portOneResponse.json() as { status?: string };
  if (verification.status !== "VERIFIED") {
    return jsonResponse(409, { message: "PASS 본인인증이 완료된 상태가 아닙니다." });
  }

  const updateResponse = await fetch(
    `${supabaseUrl}/rest/v1/profiles?id=eq.${encodeURIComponent(user.id)}`,
    {
      method: "PATCH",
      headers: {
        apikey: serviceRoleKey,
        Authorization: `Bearer ${serviceRoleKey}`,
        "Content-Type": "application/json",
        Prefer: "return=minimal",
      },
      body: JSON.stringify({
        verified: true,
        verified_at: new Date().toISOString(),
        verification_provider: "portone",
        verification_reference: identityVerificationId,
      }),
    },
  );
  if (!updateResponse.ok) {
    return jsonResponse(500, { message: "본인인증 상태를 저장하지 못했습니다." });
  }

  return jsonResponse(200, {
    verified: true,
    message: "PASS 본인인증이 완료되었습니다.",
  });
});
