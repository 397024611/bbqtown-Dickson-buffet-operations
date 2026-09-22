import { authorized } from "../../_lib/admin-auth";
import { ensureVoucherSchema } from "../../_lib/vouchers";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  if (!authorized(request)) return Response.json({ error: "Staff sign-in required" }, { status: 401 });
  try {
    const sql = await ensureVoucherSchema();
    const [stats] = await sql`
      SELECT
        count(*)::int AS total_issued,
        count(*) FILTER (WHERE voucher_date = (now() AT TIME ZONE 'Australia/Sydney')::date)::int AS issued_today,
        count(*) FILTER (WHERE status = 'issued' AND expires_at > now())::int AS active,
        count(*) FILTER (WHERE status = 'redeemed')::int AS redeemed,
        count(*) FILTER (WHERE status = 'issued' AND expires_at <= now())::int AS expired
      FROM street_vouchers
    `;
    return Response.json({
      totalIssued: Number(stats?.total_issued || 0),
      issuedToday: Number(stats?.issued_today || 0),
      active: Number(stats?.active || 0),
      redeemed: Number(stats?.redeemed || 0),
      expired: Number(stats?.expired || 0),
    });
  } catch (error) {
    return Response.json({ error: error instanceof Error ? error.message : "Unable to load voucher stats" }, { status: 500 });
  }
}

export async function POST(request: Request) {
  if (!authorized(request)) return Response.json({ error: "Staff sign-in required" }, { status: 401 });
  const { code, staff } = await request.json() as { code?: string; staff?: string };
  const clean = String(code || "").trim().toUpperCase();
  if (!/^BT10-[A-F0-9]{8}$/.test(clean)) return Response.json({ error: "Invalid voucher code" }, { status: 400 });
  try {
    const sql = await ensureVoucherSchema();
    const [row] = await sql`
      UPDATE street_vouchers
      SET status = 'redeemed', redeemed_at = now(), redeemed_by = ${String(staff || "Staff")}
      WHERE code = ${clean} AND valid_from <= now() AND expires_at > now() AND status = 'issued'
      RETURNING code, discount_percent, valid_from, expires_at, redeemed_at
    `;
    if (!row) {
      const [voucher] = await sql`
        SELECT status, valid_from, expires_at, valid_from > now() AS not_yet_valid, expires_at <= now() AS expired
        FROM street_vouchers
        WHERE code = ${clean}
        LIMIT 1
      `;
      if (voucher?.status === "issued" && voucher?.not_yet_valid) {
        return Response.json({ error: "Voucher cannot be used on the day it is issued. It is valid from tomorrow.", validFrom: voucher.valid_from }, { status: 409 });
      }
      if (voucher?.status === "issued" && voucher?.expired) {
        return Response.json({ error: "Voucher has expired" }, { status: 409 });
      }
      if (voucher?.status === "redeemed") {
        return Response.json({ error: "Voucher has already been redeemed" }, { status: 409 });
      }
      return Response.json({ error: "Voucher is invalid" }, { status: 409 });
    }
    return Response.json({ ok: true, code: row.code, discountPercent: row.discount_percent, expiresAt: row.expires_at, redeemedAt: row.redeemed_at });
  } catch (error) {
    return Response.json({ error: error instanceof Error ? error.message : "Unable to redeem voucher" }, { status: 500 });
  }
}
