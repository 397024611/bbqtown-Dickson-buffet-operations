"use client";

import { useEffect, useState } from "react";

export default function VouchersPage() {
  const [code, setCode] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [stats, setStats] = useState<{ totalIssued: number; issuedToday: number; active: number; redeemed: number; expired: number } | null>(null);

  async function loadStats() {
    try {
      const res = await fetch("/api/vouchers/redeem", { cache: "no-store" });
      const body = await res.json();
      if (res.ok) setStats(body);
    } catch {}
  }

  useEffect(() => {
    loadStats();
  }, []);

  async function redeem(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    const res = await fetch("/api/vouchers/redeem", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code }),
    });
    const body = await res.json();
    setBusy(false);
    setMessage(
      res.ok
        ? `✓ Redeemed ${body.code} · Apply 10% off to ONE guest only`
        : `✕ ${body.error || "Unable to redeem"}`
    );
    if (res.ok) {
      setCode("");
      loadStats();
    }
  }

  return (
    <main style={{ minHeight: "100vh", background: "#f5f4ef", color: "#172018", padding: 32, fontFamily: "Arial, sans-serif" }}>
      <section style={{ maxWidth: 620, margin: "50px auto", background: "white", borderRadius: 16, padding: 30 }}>
        <img src="/bbqtown-logo.svg?v=20260908-10" alt="BBQTOWN Korean BBQ Buffet" style={{ display: "block", width: "min(220px, 70%)", height: "auto", margin: "0 auto 8px" }} />
        <p style={{ textAlign: "center", letterSpacing: 1, fontWeight: 800, fontSize: 12 }}>DICKSON · STAFF ONLY</p>
        <button onClick={() => window.history.back()} style={{ minHeight: 42, border: "1px solid #ccd4cd", borderRadius: 8, background: "white", color: "#172018", fontWeight: 800, padding: "0 14px" }}>← BACK TO MANAGER</button>
        <h1>Redeem 10% voucher</h1>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))", gap: 10, margin: "18px 0 24px" }}>
          <div style={{ border: "1px solid #dce4dd", borderRadius: 12, padding: 14, background: "#f8faf8" }}>
            <div style={{ fontSize: 12, fontWeight: 800, color: "#68736b" }}>TOTAL VOUCHERS ISSUED</div>
            <div style={{ fontSize: 30, fontWeight: 900, marginTop: 4 }}>{stats?.totalIssued ?? "—"}</div>
          </div>
          <div style={{ border: "1px solid #ead7a8", borderRadius: 12, padding: 14, background: "#fff8e7" }}>
            <div style={{ fontSize: 12, fontWeight: 800, color: "#80652b" }}>ISSUED TODAY</div>
            <div style={{ fontSize: 30, fontWeight: 900, marginTop: 4 }}>{stats?.issuedToday ?? "—"}</div>
          </div>
          <div style={{ border: "1px solid #bfe0cb", borderRadius: 12, padding: 14, background: "#edf8f1" }}>
            <div style={{ fontSize: 12, fontWeight: 800, color: "#2b7550" }}>ACTIVE / UNUSED</div>
            <div style={{ fontSize: 30, fontWeight: 900, marginTop: 4 }}>{stats?.active ?? "—"}</div>
          </div>
          <div style={{ border: "1px solid #c9d8ef", borderRadius: 12, padding: 14, background: "#eef4fc" }}>
            <div style={{ fontSize: 12, fontWeight: 800, color: "#426998" }}>REDEEMED</div>
            <div style={{ fontSize: 30, fontWeight: 900, marginTop: 4 }}>{stats?.redeemed ?? "—"}</div>
          </div>
        </div>
        <p>
          <strong>IMPORTANT: 10% OFF IS FOR ONE PERSON ONLY.</strong> If a table has 5 guests and only one guest has a voucher, apply 10% off to that one guest&apos;s buffet price only. Do not discount the whole table or bill.
        </p>
        <p>Each voucher starts the day after issue, is valid for 14 full calendar days, and can only be used once. Check the customer&apos;s Google review before redeeming.</p>
        <form onSubmit={redeem}>
          <input autoFocus value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="BT10-1234ABCD" style={{ width: "100%", minHeight: 58, fontSize: 22, fontWeight: 800, padding: "0 15px", border: "1px solid #ccd4cd", borderRadius: 9, boxSizing: "border-box" }} />
          <button disabled={busy} style={{ marginTop: 12, width: "100%", minHeight: 58, border: 0, borderRadius: 9, background: "#318a5b", color: "white", fontWeight: 900, fontSize: 17 }}>{busy ? "CHECKING…" : "REDEEM 10% OFF VOUCHER"}</button>
        </form>
        {message && <p style={{ marginTop: 18, color: message.startsWith("✓") ? "#318a5b" : "#d64545", fontWeight: 800 }}>{message}</p>}
        <hr style={{ border: 0, borderTop: "1px solid #e2e7e2", margin: "28px 0" }} />
        <p><strong>Customer reward page:</strong><br />https://bbqtowndickson.com/voucher</p>
      </section>
    </main>
  );
}
