// Explicit real inference through the user's configured native Hermes route.
import {chromium} from 'playwright';
import {spawn} from 'node:child_process';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {serve} from './serve-browser-tools.mjs';
const source=process.argv[2];if(!source)throw Error('Usage: node scripts/verify-hermes-live.mjs /path/to/native/hermes [model]');
const model=process.argv[3]||'mishima';
const evidence={model,modelFixture:false,attempts:[],status:'started'};
const active=new Set();
const {server,url}=await serve(undefined,0,{handleRequest:async(req,res)=>{
 if(req.url!=='/live-model')return false;
 if(req.method!=='POST' || req.headers.origin!=='http://'+req.headers.host){res.writeHead(403);res.end();return true;}
 const incoming=[];let inputBytes=0;for await(const c of req){inputBytes+=c.length;if(inputBytes>1048576){res.writeHead(413);res.end();return true;}incoming.push(c);}
 const body=Buffer.concat(incoming).toString('utf8');const payload=JSON.parse(body);if(payload.model!==model){res.writeHead(403);res.end();return true;}
 const attempt={stream:payload.stream,roles:payload.messages.map(m=>m.role),toolResults:payload.messages.filter(m=>m.role==='tool'),requestedAt:new Date().toISOString()};evidence.attempts.push(attempt);
 const child=spawn(resolve(source,'venv/bin/python'),[resolve('scripts/hermes-model-relay.py'),source,model],{stdio:['pipe','pipe','ignore']});active.add(child);
 child.stdin.end(body);let metadata=Buffer.alloc(0),started=false,errorBody='';
 child.stdout.on('data',chunk=>{
  if(!started){metadata=Buffer.concat([metadata,chunk]);const newline=metadata.indexOf(10);if(newline<0)return;
   let head;try{head=JSON.parse(metadata.subarray(0,newline));}catch{child.kill();res.writeHead(502);res.end();return;}
   attempt.status=head.status;started=true;res.writeHead(head.status,{'Content-Type':head.contentType,'Cache-Control':'no-store'});res.flushHeaders();chunk=metadata.subarray(newline+1);
  }
  if(chunk.length){if(attempt.status>=400 && errorBody.length<4096)errorBody+=chunk.toString('utf8');attempt.responseBytes=(attempt.responseBytes||0)+chunk.length;res.write(chunk);}
 });
 child.on('error',()=>{if(!started)res.writeHead(502);res.end();active.delete(child);});
 child.on('close',code=>{try{const e=JSON.parse(errorBody).error;attempt.error={code:e.code,type:e.type,message:e.message};}catch{}if(!started){res.writeHead(502,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{type:'relay_error',code:'native_runtime_unavailable'}}));}else res.end();attempt.relayExit=code;active.delete(child);});
 res.on('close',()=>{if(!res.writableEnded)child.kill();});return true;
}});
let browser;
try {
 browser=await chromium.launch({headless:true,executablePath:process.env.KURO_CHROMIUM_PATH});
 const page=await browser.newPage();await page.goto(url);
 const result=await page.evaluate(async model=>{
  const {createHermesBrowser}=await import('./hermes.js');let text='',deltas=0;
  const host=createHermesBrowser({model,modelEndpoint:'/live-model',capabilities:['tool/js','tool/python','tool/python-js-bridge','network/model','session/persist'],timeoutMs:90000});
  try {const result=await host.run({sessionId:'live-'+crypto.randomUUID(),prompt:'Use the js tool to compute 21 * 2 and the python tool to compute sum([20,22]). Execute both tools, then report their actual results briefly.'},{onDelta:delta=>{text+=delta;deltas++;}});return {result,text,deltas};}
  catch(error){return {error:String(error.message)};}
 },model);
 evidence.browser={error:result.error,completed:result.result?.completed,failed:result.result?.failed,final:result.result?.final_response,streamedText:result.text,deltas:result.deltas,saved:result.result?.saved};
 const tools=result.result?.messages?.filter(m=>m.role==='tool')||[];
 evidence.toolResults=tools;
 evidence.liveToolRoundTrip=tools.some(m=>m.name==='js'||result.result.messages.some(a=>a.tool_calls?.some(t=>t.id===m.tool_call_id&&t.function.name==='js'))) && tools.some(m=>result.result.messages.some(a=>a.tool_calls?.some(t=>t.id===m.tool_call_id&&t.function.name==='python'))) && tools.every(m=>{try{const v=JSON.parse(m.content);return v.result===42&&v.receipt['kuro/exit-code']===0;}catch{return false;}});
 evidence.status=evidence.liveToolRoundTrip&&result.result?.completed&&result.deltas>0 ? 'qualified' : 'blocked';
 evidence.provenance=JSON.parse(await readFile('target/browser-tools/hermes-source.json'));
 evidence.sourceSha256=createHash('sha256').update(await readFile('src/kuro/host/hermes/adapter.py')).digest('hex');
 await writeFile('docs/verification/hermes-browser-live.json',JSON.stringify(evidence,null,2)+'\n');
 console.log(JSON.stringify({status:evidence.status,model,attempts:evidence.attempts.map(a=>({status:a.status,stream:a.stream})),browser:evidence.browser,liveToolRoundTrip:evidence.liveToolRoundTrip}));
 assert.equal(evidence.status,'qualified','real model qualification failed; inspect recorded evidence');
}finally{for(const child of active)child.kill();await browser?.close();server.closeAllConnections();await new Promise(r=>server.close(r));}
