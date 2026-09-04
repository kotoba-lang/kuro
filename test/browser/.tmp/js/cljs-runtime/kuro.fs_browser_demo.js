goog.provide('kuro.fs_browser_demo');
if((typeof kuro !== 'undefined') && (typeof kuro.fs_browser_demo !== 'undefined') && (typeof kuro.fs_browser_demo.worker !== 'undefined')){
} else {
kuro.fs_browser_demo.worker = (new Worker("/kuro-opfs-worker.js"));
}
if((typeof kuro !== 'undefined') && (typeof kuro.fs_browser_demo !== 'undefined') && (typeof kuro.fs_browser_demo.pending !== 'undefined')){
} else {
kuro.fs_browser_demo.pending = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
}
if((typeof kuro !== 'undefined') && (typeof kuro.fs_browser_demo !== 'undefined') && (typeof kuro.fs_browser_demo.next_id !== 'undefined')){
} else {
kuro.fs_browser_demo.next_id = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
}
/**
 * postMessage round-trip returning a JS Promise of the reply.
 */
kuro.fs_browser_demo.call_worker = (function kuro$fs_browser_demo$call_worker(op,payload){
return (new Promise((function (resolve,_reject){
var id = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(kuro.fs_browser_demo.next_id,cljs.core.inc);
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(kuro.fs_browser_demo.pending,cljs.core.assoc,id,resolve);

return kuro.fs_browser_demo.worker.postMessage(({"kuro.opfs/type": "request", "kuro.opfs/id": id, "kuro.opfs/op": cljs.core.name(op), "kuro.opfs/payload": cljs.core.clj__GT_js(payload)}));
})));
});
kuro.fs_browser_demo.worker.addEventListener("message",(function (ev){
var m = cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$1(ev.data);
var id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(m,"kuro.opfs/id");
var resolve = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(kuro.fs_browser_demo.pending),id);
if(cljs.core.truth_(resolve)){
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(kuro.fs_browser_demo.pending,cljs.core.dissoc,id);

var G__20064 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(m,"kuro.opfs/result");
return (resolve.cljs$core$IFn$_invoke$arity$1 ? resolve.cljs$core$IFn$_invoke$arity$1(G__20064) : resolve.call(null,G__20064));
} else {
return null;
}
}));
kuro.fs_browser_demo.bytes__GT_js = (function kuro$fs_browser_demo$bytes__GT_js(s){
return (new Uint8Array(Array.from(cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p1__20065_SHARP_){
return (p1__20065_SHARP_.charCodeAt() - (0));
}),s))));
});
/**
 * The E2E scenario: put bytes -> cid (Worker mints, OPFS caches); write a
 *   kuro.fs file node at greeting.txt whose leaf carries that cid; ls the
 *   root; read the file back through kuro.fs read-file with get-block hitting
 *   OPFS. All through the REAL kuro.fs model.
 */
kuro.fs_browser_demo.run_scenario = (function kuro$fs_browser_demo$run_scenario(){
var st0 = kuro.fs.store.cljs$core$IFn$_invoke$arity$1("bafyrei-root");
var text_in = "hello from kuro.fs";
var content = kuro.fs_browser_demo.bytes__GT_js(text_in);
return kuro.fs_browser_demo.call_worker(new cljs.core.Keyword(null,"put","put",1299772570),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"bytes","bytes",1175866680),content], null)).then((function (reply){
var cid = cljs.core.get.cljs$core$IFn$_invoke$arity$2(reply,"cid");
var vec__20067 = kuro.fs.write(st0,"greeting.txt",content,(function (_b){
return cid;
}));
var st1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20067,(0),null);
var _cid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20067,(1),null);
var vec__20070 = kuro.fs.ls(st1,".");
var st2 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20070,(0),null);
var entries = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20070,(1),null);
var vec__20073 = kuro.fs.read_file(st2,"greeting.txt",(function (c){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(c,cid)){
return content;
} else {
return null;
}
}));
var st3 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20073,(0),null);
var bytes = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20073,(1),null);
return ({"wroteCid": cid, "readText": (cljs.core.truth_(bytes)?(new TextDecoder()).decode(bytes):null), "lsEntries": cljs.core.clj__GT_js(entries), "readMatches": (cljs.core.truth_(bytes)?cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(text_in,(new TextDecoder()).decode(bytes)):null), "receiptCount": cljs.core.count(kuro.fs.receipts(st3)), "denials": cljs.core.count(cljs.core.filter.cljs$core$IFn$_invoke$arity$2((function (p1__20066_SHARP_){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword("kuro.fs","denied","kuro.fs/denied",-2058388799),new cljs.core.Keyword("kuro.fs","type","kuro.fs/type",2010862720).cljs$core$IFn$_invoke$arity$1(p1__20066_SHARP_));
}),kuro.fs.receipts(st3)))});
}));
});
(window.fsE2E = (function (){
return kuro.fs_browser_demo.run_scenario().then((function (results){
(window.__fsResults = results);

return results;
}));
}));

//# sourceMappingURL=kuro.fs_browser_demo.js.map
