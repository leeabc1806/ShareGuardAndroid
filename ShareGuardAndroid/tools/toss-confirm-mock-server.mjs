import http from "node:http";

const port = Number(process.env.PORT || 8080);

const server = http.createServer((req, res) => {
  if (req.method !== "POST" || req.url !== "/payments/toss/confirm") {
    res.writeHead(404, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ message: "Not found" }));
    return;
  }

  let rawBody = "";
  req.on("data", chunk => {
    rawBody += chunk;
  });
  req.on("end", () => {
    const body = rawBody ? JSON.parse(rawBody) : {};
    const { paymentKey, orderId, amount } = body;

    if (!paymentKey || !orderId || !amount) {
      res.writeHead(400, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ message: "paymentKey, orderId, amount are required" }));
      return;
    }

    console.log(`[mock-confirm] orderId=${orderId} amount=${amount} paymentKey=${paymentKey}`);
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({
      status: "DONE",
      paymentKey,
      orderId,
      totalAmount: amount
    }));
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Toss confirm mock server listening on http://0.0.0.0:${port}`);
});
