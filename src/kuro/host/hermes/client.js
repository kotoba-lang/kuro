// A synchronous Hermes Worker may wait; the UI and tool Workers remain asynchronous.
import {createToolHost, toolDefinitions} from '../tools/client.js';
export function createHermesBrowser({capabilities = [], modelEndpoint, model, contextLength = 128000, modelHeaders = {}, timeoutMs = 120000,
  workerUrl = new URL('./hermes-worker.js', import.meta.url)} = {}) {
  if(typeof model !== 'string' || !model) throw Error('model is required');
  if(!Number.isSafeInteger(contextLength) || contextLength < 64000) throw Error('contextLength must be at least 64000');
  if(!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2147483647) throw Error('invalid deadline');
  const grants = new Set(capabilities);
  if (!grants.has('tool/python') || !grants.has('tool/python-js-bridge')) throw Error('Hermes requires trusted Python bridge grants');
  if (!crossOriginIsolated || typeof SharedArrayBuffer === 'undefined') throw Error('Hermes bridge requires COOP/COEP cross-origin isolation');
  const endpoint = new URL(modelEndpoint, location.href);
  if (!['http:', 'https:'].includes(endpoint.protocol) || endpoint.username || endpoint.password) throw Error('invalid model endpoint');
  const host = createToolHost({capabilities, timeoutMs: Math.min(timeoutMs, 30000)});
  return {run({sourceUrl, prompt, code}, {signal} = {}) {
    sourceUrl = new URL(sourceUrl || './hermes-probe-source.tar.gz', import.meta.url).href;
    if(new URL(sourceUrl).origin !== location.origin) throw Error('source must be served on the application origin');
    return new Promise((resolve, reject) => {
      const worker = new Worker(workerUrl, {type:'module'});
      const controller = new AbortController();
      let done = false;
      const finish = (error, value) => {if(done)return; done=true; clearTimeout(timer); controller.abort(); worker.terminate(); signal?.removeEventListener('abort',abort); error ? reject(error) : resolve(value);};
      const abort = () => finish(Error('cancelled'));
      const timer = setTimeout(()=>finish(Error('Hermes deadline exceeded')),timeoutMs);
      signal?.addEventListener('abort',abort,{once:true});
      if(signal?.aborted) return abort();
      worker.onerror = event => finish(Error(event.message));
      worker.onmessage = async ({data}) => {
        if(data.kind==='done') return finish(data.error ? Error(data.error) : null,data.result);
        if(data.kind!=='rpc') return;
        const state=new Int32Array(data.buffer,0,2), body=new Uint8Array(data.buffer,8);
        try {
          let result;
          if(data.operation==='tool') result=await host.call(data.payload,{signal:controller.signal});
          else if(data.operation==='model') {
            if(!grants.has('network/model')) throw Error('missing-capabilities: network/model');
            const requestBody=JSON.stringify(data.payload);
            if(new TextEncoder().encode(requestBody).length>body.length) throw Error('model request exceeds byte limit');
            const response=await fetch(endpoint,{method:'POST',headers:{...modelHeaders,'Content-Type':'application/json'},body:requestBody,signal:controller.signal,credentials:'omit',redirect:'error'});
            const reader=response.body.getReader();const chunks=[];let length=0;
            try {
              for(;;) {const {done,value}=await reader.read();if(done)break;length+=value.byteLength;
                if(length>body.length){await reader.cancel();throw Error('model response exceeds byte limit');}chunks.push(value);}
            } finally {reader.releaseLock();}
            const joined=new Uint8Array(length);let offset=0;for(const chunk of chunks){joined.set(chunk,offset);offset+=chunk.byteLength;}
            result={status:response.status,body:JSON.parse(new TextDecoder().decode(joined))};
          } else throw Error('unknown bridge operation');
          const encoded=new TextEncoder().encode(JSON.stringify({result}));
          if(encoded.length>body.length) throw Error('bridge response exceeds byte limit');
          body.set(encoded); Atomics.store(state,1,encoded.length); Atomics.store(state,0,1);
        } catch(error) {
          const encoded=new TextEncoder().encode(JSON.stringify({error:String(error.message).slice(0,4096)}));
          body.set(encoded); Atomics.store(state,1,encoded.length); Atomics.store(state,0,1);
        } finally {Atomics.notify(state,0);}
      };
      worker.postMessage({sourceUrl,prompt,code,model,contextLength,tools:toolDefinitions.filter(t=>grants.has(`tool/${t.function.name}`))});
    });
  }};
}
