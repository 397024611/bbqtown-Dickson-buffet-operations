import { accessFromRequest } from "../../_lib/device-auth";
import { ensureOpsSchema, runOpsMaintenance } from "../../_lib/ops-store";

export const dynamic = "force-dynamic";

type Sql = Awaited<ReturnType<typeof ensureOpsSchema>>;
type ReadOptions = { section?: number; includeLogs?: boolean; knownVersion?: number };

type FoodRow = {
  id: number | string;
  name: string;
  category: string;
  section: number | string;
  status: string;
  kitchen: string;
  start_at: number | string;
  requested_at: number | string | null;
  preparing_at: number | string | null;
  ready_at: number | string | null;
  stopped_at: number | string | null;
  closed_at: number | string | null;
};

function foodJson(row: FoodRow) {
  return {
    id: Number(row.id),
    name: row.name,
    category: row.category,
    section: Number(row.section),
    status: row.status,
    kitchen: row.kitchen,
    start: Number(row.start_at || 0),
    requestedAt: Number(row.requested_at || 0),
    preparingAt: Number(row.preparing_at || 0),
    readyAt: Number(row.ready_at || 0),
    stoppedAt: Number(row.stopped_at || 0),
    closedAt: Number(row.closed_at || 0),
  };
}

function sectionValue(value: unknown) {
  const section = Number(value || 0);
  return section === 1 || section === 2 ? section : 0;
}

async function readState(sql: Sql, options: ReadOptions = {}) {
  const section = sectionValue(options.section);
  const version = options.knownVersion ?? Number((await sql`SELECT version FROM buffet_service WHERE id=1`)[0]?.version || 0);
  const foods = section
    ? await sql`
        SELECT id,name,category,section,status,kitchen,start_at,requested_at,preparing_at,ready_at,stopped_at,closed_at
        FROM buffet_foods
        WHERE active=true AND section=${section}
        ORDER BY lower(name), name, id
      `
    : await sql`
        SELECT id,name,category,section,status,kitchen,start_at,requested_at,preparing_at,ready_at,stopped_at,closed_at
        FROM buffet_foods
        WHERE active=true
        ORDER BY lower(name), name, id
      `;

  let logs: any[] = [];
  if (options.includeLogs !== false) {
    logs = await sql`
      SELECT created_at,action,food_name,section_label,detail
      FROM buffet_events
      ORDER BY id DESC
      LIMIT 100
    ` as any[];
  }

  return {
    state: {
      foods: (foods as FoodRow[]).map(foodJson),
      logs: logs.map((row) => ({
        at: Number(row.created_at || 0),
        action: row.action || "",
        food: row.food_name || "Store",
        section: row.section_label || "All Sections",
        detail: row.detail || "",
      })),
    },
    updatedAt: version,
  };
}

async function conflict(sql: Sql, options: ReadOptions = {}) {
  return Response.json({ error: "STATE_CONFLICT", ...(await readState(sql, options)) }, { status: 409 });
}

function successVersion(rows: any): number {
  if (!Array.isArray(rows) || !rows.length) return 0;
  return Number(rows[0]?.version || 0);
}

export async function GET(request: Request) {
  try {
    const sql = await ensureOpsSchema();
    const url = new URL(request.url);
    const since = Number(url.searchParams.get("since") || 0);
    const section = sectionValue(url.searchParams.get("section"));
    const includeLogs = url.searchParams.get("logs") !== "0";
    if (since > 0) {
      const [service] = await sql`SELECT version FROM buffet_service WHERE id=1`;
      const current = Number(service?.version || 0);
      if (current === since) return Response.json({ unchanged: true, updatedAt: current });
      return Response.json(await readState(sql, { section, includeLogs, knownVersion: current }));
    }
    return Response.json(await readState(sql, { section, includeLogs }));
  } catch (error) {
    console.error("ops/live GET failed", error);
    return Response.json({ error: error instanceof Error ? error.message : "Database unavailable" }, { status: 500 });
  }
}

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const sql = await ensureOpsSchema();
    const requestedExpected = Number(body.expectedUpdatedAt || 0);
    const action = String(body.action || "");
    // Runtime floor/kitchen actions are item-scoped and already have their own SQL
    // preconditions. A global service-version CAS caused unrelated tablets to
    // conflict with each other and could permanently wedge the offline queue.
    // Keep optimistic concurrency only for manager dish-definition changes.
    const expected = action.startsWith("dish_") ? requestedExpected : 0;
    const mutationId = String(body.mutationId || "").trim().slice(0, 160);
    const responseSection = sectionValue(body.responseSection);
    const includeLogs = body.includeLogs !== false;
    const responseOptions = { section: responseSection, includeLogs };
    const device = accessFromRequest(request);
    const deviceId = String(device?.deviceId || "").slice(0, 120);
    const now = Date.now();

    if (mutationId) {
      const [prior] = await sql`SELECT response_version FROM ops_mutations WHERE mutation_id=${mutationId} LIMIT 1`;
      if (prior) {
        const state = await readState(sql, responseOptions);
        return Response.json({ ...state, duplicate: true });
      }
    }

    let result: any = [];

    if (action === "status") {
      const id = Number(body.id);
      const next = String(body.status || "").toUpperCase();
      if (!id) return Response.json({ error: "Food id required" }, { status: 400 });
      if (!["GOOD", "LOW", "EMPTY"].includes(next)) return Response.json({ error: "Invalid status" }, { status: 400 });
      const detail = next === "EMPTY" ? "Urgent kitchen request" : next === "LOW" ? "Prepare next tray" : "Floor confirmed display is good";

      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1
            AND (${expected}::bigint=0 OR version=${expected}::bigint)
            AND EXISTS (SELECT 1 FROM buffet_foods WHERE id=${id} AND active=true)
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET status=${next},
              kitchen=CASE
                WHEN ${next} IN ('LOW','EMPTY') AND COALESCE(food.kitchen,'idle')='idle' THEN 'requested'
                WHEN ${next}='GOOD' AND food.status<>'GOOD' AND food.kitchen='requested' THEN 'idle'
                ELSE food.kitchen
              END,
              requested_at=CASE
                WHEN ${next} IN ('LOW','EMPTY') AND COALESCE(food.kitchen,'idle')='idle' THEN ${now}
                WHEN ${next}='GOOD' AND food.status<>'GOOD' AND food.kitchen='requested' THEN NULL
                ELSE food.requested_at
              END,
              ready_at=CASE
                WHEN ${next} IN ('LOW','EMPTY') AND COALESCE(food.kitchen,'idle')='idle' THEN NULL
                WHEN ${next}='GOOD' AND food.status<>'GOOD' AND food.kitchen='requested' THEN NULL
                ELSE food.ready_at
              END,
              updated_at=${now}
          WHERE food.id=${id} AND food.active=true AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id,food.name,food.section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},${`${next} STATUS`},id,name,'Section '||section::text,${detail} FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "kitchen_prepare") {
      const id = Number(body.id);
      if (!id) return Response.json({ error: "Food id required" }, { status: 400 });
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1
            AND (${expected}::bigint=0 OR version=${expected}::bigint)
            AND EXISTS (SELECT 1 FROM buffet_foods WHERE id=${id} AND active=true AND kitchen='requested')
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET kitchen='preparing',preparing_at=${now},updated_at=${now}
          WHERE food.id=${id} AND food.active=true AND food.kitchen='requested' AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id,food.name,food.section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},'PREPARING STARTED',id,name,'Section '||section::text,'Kitchen accepted request' FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "kitchen_ready") {
      const id = Number(body.id);
      if (!id) return Response.json({ error: "Food id required" }, { status: 400 });
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1
            AND (${expected}::bigint=0 OR version=${expected}::bigint)
            AND EXISTS (SELECT 1 FROM buffet_foods WHERE id=${id} AND active=true AND kitchen='preparing')
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET kitchen='ready',ready_at=${now},updated_at=${now}
          WHERE food.id=${id} AND food.active=true AND food.kitchen='preparing' AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id,food.name,food.section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},'READY TO REFILL',id,name,'Section '||section::text,'Kitchen finished; waiting for FOH confirmation' FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "refilled") {
      const id = Number(body.id);
      if (!id) return Response.json({ error: "Food id required" }, { status: 400 });
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1
            AND (${expected}::bigint=0 OR version=${expected}::bigint)
            AND EXISTS (SELECT 1 FROM buffet_foods WHERE id=${id} AND active=true)
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET kitchen='idle',status='GOOD',start_at=${now},requested_at=NULL,preparing_at=NULL,ready_at=NULL,stopped_at=NULL,closed_at=NULL,updated_at=${now}
          WHERE food.id=${id} AND food.active=true AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id,food.name,food.section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},'NEW BATCH STARTED BY FOH',id,name,'Section '||section::text,'Refilled on floor; new service timer started' FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "start_all") {
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now},is_open=true
          WHERE id=1 AND (${expected}::bigint=0 OR version=${expected}::bigint)
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET start_at=${now},status='GOOD',kitchen='idle',requested_at=NULL,preparing_at=NULL,ready_at=NULL,stopped_at=NULL,closed_at=NULL,updated_at=${now}
          WHERE food.active=true AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_name,section_label,detail)
          SELECT ${now},'BULK START · '||(SELECT COUNT(*)::text FROM changed)||' NEW BATCHES','Store','All Sections','All items started together'
          WHERE EXISTS (SELECT 1 FROM gate)
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "close_all") {
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now},is_open=false
          WHERE id=1 AND (${expected}::bigint=0 OR version=${expected}::bigint)
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET closed_at=${now},stopped_at=${now},status='CLOSED',kitchen='idle',requested_at=NULL,preparing_at=NULL,ready_at=NULL,updated_at=${now}
          WHERE food.active=true AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_name,section_label,detail)
          SELECT ${now},'END OF DAY · '||(SELECT COUNT(*)::text FROM changed)||' DISHES CLEARED','Store','All Sections','All dishes cleared and kitchen tasks cancelled'
          WHERE EXISTS (SELECT 1 FROM gate)
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "dish_add") {
      const name = String(body.name || "").trim();
      const category = String(body.category || "Other").trim() || "Other";
      const section = Number(body.section) === 2 ? 2 : 1;
      if (!name) return Response.json({ error: "Dish name is required" }, { status: 400 });
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1 AND (${expected}::bigint=0 OR version=${expected}::bigint)
          RETURNING version
        ), changed AS (
          INSERT INTO buffet_foods(name,category,section,status,kitchen,start_at,sort_order,active,updated_at)
          SELECT ${name},${category},${section},'GOOD','idle',0,COALESCE((SELECT MAX(sort_order)+1 FROM buffet_foods),0),true,${now}
          WHERE EXISTS (SELECT 1 FROM gate)
          RETURNING id,name,section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},'DISH ADDED',id,name,'Section '||section::text,${`${category} · Section ${section}`} FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "dish_update") {
      const id = Number(body.id);
      const name = String(body.name || "").trim();
      const category = String(body.category || "Other").trim() || "Other";
      const section = Number(body.section) === 2 ? 2 : 1;
      if (!id) return Response.json({ error: "Food id required" }, { status: 400 });
      if (!name) return Response.json({ error: "Dish name is required" }, { status: 400 });
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1 AND (${expected}::bigint=0 OR version=${expected}::bigint) AND EXISTS (SELECT 1 FROM buffet_foods WHERE id=${id} AND active=true)
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET name=${name},category=${category},section=${section},updated_at=${now}
          WHERE food.id=${id} AND food.active=true AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id,food.name,food.section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},'DISH UPDATED',id,name,'Section '||section::text,${`${category} · Section ${section}`} FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else if (action === "dish_delete") {
      const id = Number(body.id);
      if (!id) return Response.json({ error: "Food id required" }, { status: 400 });
      result = await sql`
        WITH gate AS (
          UPDATE buffet_service
          SET version=GREATEST(version+1,${now}),updated_at=${now}
          WHERE id=1 AND (${expected}::bigint=0 OR version=${expected}::bigint) AND EXISTS (SELECT 1 FROM buffet_foods WHERE id=${id} AND active=true)
          RETURNING version
        ), changed AS (
          UPDATE buffet_foods food
          SET active=false,kitchen='idle',updated_at=${now}
          WHERE food.id=${id} AND food.active=true AND EXISTS (SELECT 1 FROM gate)
          RETURNING food.id,food.name,food.section
        ), event_row AS (
          INSERT INTO buffet_events(created_at,action,food_id,food_name,section_label,detail)
          SELECT ${now},'DISH REMOVED',id,name,'Section '||section::text,'Removed from active buffet list' FROM changed
          RETURNING id
        )
        SELECT version FROM gate
      `;
    } else {
      return Response.json({ error: "Unknown action" }, { status: 400 });
    }

    let version = successVersion(result);
    if (!version) {
      const operationalAction = ["status", "kitchen_prepare", "kitchen_ready", "refilled", "start_all", "close_all"].includes(action);
      if (mutationId && operationalAction) {
        // A queued operation can become obsolete because another tablet already
        // performed the same or a later state transition. Treat that as a
        // resolved no-op instead of returning 409 forever.
        const [service] = await sql`SELECT version FROM buffet_service WHERE id=1`;
        version = Number(service?.version || Date.now());
        await sql`
          INSERT INTO ops_mutations(mutation_id,created_at,device_id,response_version)
          VALUES (${mutationId},${now},${deviceId},${version})
          ON CONFLICT (mutation_id) DO UPDATE SET response_version=GREATEST(ops_mutations.response_version,EXCLUDED.response_version)
        `;
        return Response.json({ ...(await readState(sql, { ...responseOptions, knownVersion: version })), resolvedStaleMutation: true });
      }
      return conflict(sql, responseOptions);
    }

    if (mutationId) {
      await sql`
        INSERT INTO ops_mutations(mutation_id,created_at,device_id,response_version)
        VALUES (${mutationId},${now},${deviceId},${version})
        ON CONFLICT (mutation_id) DO UPDATE SET response_version=GREATEST(ops_mutations.response_version,EXCLUDED.response_version)
      `;
    }
    void runOpsMaintenance(sql);
    return Response.json(await readState(sql, { ...responseOptions, knownVersion: version }));
  } catch (error) {
    console.error("ops/live POST failed", error);
    return Response.json({ error: error instanceof Error ? error.message : "Database unavailable" }, { status: 500 });
  }
}
