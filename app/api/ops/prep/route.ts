import { accessFromRequest } from "../../_lib/device-auth";
import { ensureOpsSchema } from "../../_lib/ops-store";

export const dynamic = "force-dynamic";
const clean=(v:unknown,n=500)=>String(v??"").trim().slice(0,n);
let mutationPayloadReady:Promise<void>|null=null;

async function ensureMutationPayload(sql:Awaited<ReturnType<typeof ensureOpsSchema>>){
  if(!mutationPayloadReady){
    mutationPayloadReady=(async()=>{
      await sql`ALTER TABLE ops_mutations ADD COLUMN IF NOT EXISTS response_payload jsonb NOT NULL DEFAULT '{}'::jsonb`;
    })().catch((error)=>{mutationPayloadReady=null;throw error});
  }
  await mutationPayloadReady;
}

async function replayMutation(sql:Awaited<ReturnType<typeof ensureOpsSchema>>,mutationId:string){
  if(!mutationId)return null;
  const [row]=await sql`SELECT response_payload FROM ops_mutations WHERE mutation_id=${mutationId} LIMIT 1`;
  if(!row)return null;
  const payload=row.response_payload as Record<string,unknown>|null;
  return payload&&Object.keys(payload).length?payload:{error:"MUTATION_IN_PROGRESS"};
}

export async function GET(request:Request){
  try{
    const sql=await ensureOpsSchema();
    const url=new URL(request.url);
    const date=clean(url.searchParams.get('date'),20)||new Date().toISOString().slice(0,10);
    const section=clean(url.searchParams.get('section'),20).toUpperCase();
    const items=section&&section!=='ALL'
      ? await sql`SELECT * FROM kitchen_prep_items WHERE prep_date=${date} AND section IN (${section},'ALL') ORDER BY completed,sort_order,id`
      : await sql`SELECT * FROM kitchen_prep_items WHERE prep_date=${date} ORDER BY completed,section,sort_order,id`;
    return Response.json({date,items});
  }catch(error){return Response.json({error:error instanceof Error?error.message:'Database unavailable'},{status:500})}
}

export async function POST(request:Request){
  try{
    const body=await request.json();
    const sql=await ensureOpsSchema();
    const action=clean(body.action,40);
    const now=Date.now();
    const mutationId=clean(body.mutationId,160);
    const deviceId=clean(accessFromRequest(request)?.deviceId,120);

    if(mutationId){
      await ensureMutationPayload(sql);
      // If a previous request claimed an idempotency key but never stored its
      // response (network/server interruption or a no-op update), do not leave
      // every device retrying HTTP 409 forever.
      await sql`DELETE FROM ops_mutations
                WHERE mutation_id=${mutationId}
                  AND response_payload='{}'::jsonb
                  AND created_at < ${now-10_000}`;
      const replay=await replayMutation(sql,mutationId);
      if(replay&&!(replay as any).error)return Response.json(replay);
      if(replay&&(replay as any).error==='MUTATION_IN_PROGRESS')return Response.json(replay,{status:409});
    }

    if(action==='add'){
      const prepDate=clean(body.prepDate,20),item=clean(body.item,180);
      if(!prepDate||!item)return Response.json({error:'prepDate and item required'},{status:400});
      const section=clean(body.section,20).toUpperCase()||'ALL';
      const quantity=clean(body.quantity,80),notes=clean(body.notes,600),createdBy=clean(body.createdBy,120),sortOrder=Number(body.sortOrder)||0;

      if(mutationId){
        const rows=await sql`
          WITH claim AS (
            INSERT INTO ops_mutations(mutation_id,created_at,device_id,response_version,response_payload)
            VALUES (${mutationId},${now},${deviceId},0,'{}'::jsonb)
            ON CONFLICT (mutation_id) DO NOTHING
            RETURNING mutation_id
          ), created AS (
            INSERT INTO kitchen_prep_items (prep_date,section,item,quantity,notes,sort_order,created_at,created_by)
            SELECT ${prepDate},${section},${item},${quantity},${notes},${sortOrder},${now},${createdBy}
            WHERE EXISTS (SELECT 1 FROM claim)
            RETURNING *
          ), saved AS (
            UPDATE ops_mutations m
            SET response_payload=jsonb_build_object('ok',true,'item',to_jsonb(created))
            FROM created
            WHERE m.mutation_id=${mutationId}
            RETURNING m.response_payload
          )
          SELECT response_payload FROM saved
        `;
        if(rows.length)return Response.json(rows[0].response_payload);
        const replay=await replayMutation(sql,mutationId);
        return Response.json(replay||{error:'MUTATION_IN_PROGRESS'},{status:replay&&(replay as any).error?409:200});
      }

      const [created]=await sql`INSERT INTO kitchen_prep_items (prep_date,section,item,quantity,notes,sort_order,created_at,created_by) VALUES (${prepDate},${section},${item},${quantity},${notes},${sortOrder},${now},${createdBy}) RETURNING *`;
      return Response.json({ok:true,item:created});
    } else if(action==='toggle'){
      const id=Number(body.id); if(!id)return Response.json({error:'id required'},{status:400});
      const completed=!!body.completed;
      const completedBy=completed?clean(body.completedBy,120):'';

      if(mutationId){
        const rows=await sql`
          WITH claim AS (
            INSERT INTO ops_mutations(mutation_id,created_at,device_id,response_version,response_payload)
            VALUES (${mutationId},${now},${deviceId},0,'{}'::jsonb)
            ON CONFLICT (mutation_id) DO NOTHING
            RETURNING mutation_id
          ), updated AS (
            UPDATE kitchen_prep_items item
            SET completed=${completed},completed_at=${completed?now:null},completed_by=${completedBy}
            WHERE item.id=${id} AND EXISTS (SELECT 1 FROM claim)
            RETURNING item.*
          ), saved AS (
            UPDATE ops_mutations m
            SET response_payload=jsonb_build_object('ok',true,'item',to_jsonb(updated))
            FROM updated
            WHERE m.mutation_id=${mutationId}
            RETURNING m.response_payload
          )
          SELECT response_payload FROM saved
        `;
        if(rows.length)return Response.json(rows[0].response_payload);
        const replay=await replayMutation(sql,mutationId);
        if(replay&&!(replay as any).error)return Response.json(replay);
        const [exists]=await sql`SELECT id FROM kitchen_prep_items WHERE id=${id}`;
        if(!exists)return Response.json({error:'Prep item not found'},{status:404});
        return Response.json(replay||{error:'MUTATION_IN_PROGRESS'},{status:409});
      }

      const [updated]=await sql`UPDATE kitchen_prep_items SET completed=${completed},completed_at=${completed?now:null},completed_by=${completedBy} WHERE id=${id} RETURNING *`;
      if(!updated)return Response.json({error:'Prep item not found'},{status:404});
      return Response.json({ok:true,item:updated});
    } else if(action==='delete'){
      const id=Number(body.id); if(!id)return Response.json({error:'id required'},{status:400});
      await sql`DELETE FROM kitchen_prep_items WHERE id=${id}`;
      return Response.json({ok:true});
    } else if(action==='carry'){
      const fromDate=clean(body.fromDate,20),toDate=clean(body.toDate,20); if(!fromDate||!toDate)return Response.json({error:'fromDate and toDate required'},{status:400});
      const createdBy=clean(body.createdBy,120);
      const inserted=await sql`
        INSERT INTO kitchen_prep_items (prep_date,section,item,quantity,notes,sort_order,created_at,created_by)
        SELECT ${toDate},src.section,src.item,src.quantity,src.notes,src.sort_order,${now},${createdBy}
        FROM kitchen_prep_items src
        WHERE src.prep_date=${fromDate}
          AND src.completed=false
          AND NOT EXISTS (
            SELECT 1 FROM kitchen_prep_items dst
            WHERE dst.prep_date=${toDate}
              AND dst.section=src.section
              AND lower(dst.item)=lower(src.item)
          )
        RETURNING id
      `;
      return Response.json({ok:true,inserted:inserted.length});
    } else return Response.json({error:'Unknown action'},{status:400});
  }catch(error){
    console.error('ops/prep POST failed',error);
    return Response.json({error:error instanceof Error?error.message:'Database unavailable'},{status:500});
  }
}
