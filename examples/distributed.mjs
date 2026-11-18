// Runs every bank-withdrawal strategy with topology DISTRIBUTED (requires: docker compose --profile distributed up -d).
const B=(process.env.RACELAB_URL ?? 'http://localhost:4200')+'/api';
for (const s of ['NONE','REENTRANT_LOCK','ADVISORY_LOCK','PESSIMISTIC_LOCKING','ATOMIC_UPDATE','REDIS_LOCK']) {
  const c=await (await fetch(B+'/experiments',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({scenarioId:'bank-withdrawal',strategy:s,autoStart:true,configuration:{topology:'DISTRIBUTED',resourceCode:'ACC-A',lockTtlMs:5000}})})).json();
  if(!c.id){console.log(s,JSON.stringify(c));continue;}
  let st; for(let i=0;i<120;i++){st=(await (await fetch(`${B}/experiments/${c.id}`)).json()).status; if(['COMPLETED','FAILED','CANCELLED'].includes(st))break; await new Promise(r=>setTimeout(r,250));}
  const r=await (await fetch(`${B}/experiments/${c.id}/result`)).json();
  console.log(s.padEnd(20),st,'consistent='+r.consistent,[...new Set(r.transactions.map(t=>t.instanceId))].join('+'));
}
