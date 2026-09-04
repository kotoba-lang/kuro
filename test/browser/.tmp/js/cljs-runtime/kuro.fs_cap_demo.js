goog.provide('kuro.fs_cap_demo');
if((typeof kuro !== 'undefined') && (typeof kuro.fs_cap_demo !== 'undefined') && (typeof kuro.fs_cap_demo.worker !== 'undefined')){
} else {
kuro.fs_cap_demo.worker = (new Worker("/kuro-opfs-worker.js"));
}
if((typeof kuro !== 'undefined') && (typeof kuro.fs_cap_demo !== 'undefined') && (typeof kuro.fs_cap_demo.pending !== 'undefined')){
} else {
kuro.fs_cap_demo.pending = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
}
if((typeof kuro !== 'undefined') && (typeof kuro.fs_cap_demo !== 'undefined') && (typeof kuro.fs_cap_demo.next_id !== 'undefined')){
} else {
kuro.fs_cap_demo.next_id = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
}
kuro.fs_cap_demo.call_worker = (function kuro$fs_cap_demo$call_worker(op,payload){
return (new Promise((function (resolve,_reject){
var id = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(kuro.fs_cap_demo.next_id,cljs.core.inc);
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(kuro.fs_cap_demo.pending,cljs.core.assoc,id,resolve);

return kuro.fs_cap_demo.worker.postMessage(({"kuro.opfs/type": "request", "kuro.opfs/id": id, "kuro.opfs/op": cljs.core.name(op), "kuro.opfs/payload": cljs.core.clj__GT_js(payload)}));
})));
});
kuro.fs_cap_demo.worker.addEventListener("message",(function (ev){
var m = cljs.core.js__GT_clj.cljs$core$IFn$_invoke$arity$1(ev.data);
var id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(m,"kuro.opfs/id");
var resolve = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(kuro.fs_cap_demo.pending),id);
if(cljs.core.truth_(resolve)){
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(kuro.fs_cap_demo.pending,cljs.core.dissoc,id);

var G__20064 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(m,"kuro.opfs/result");
return (resolve.cljs$core$IFn$_invoke$arity$1 ? resolve.cljs$core$IFn$_invoke$arity$1(G__20064) : resolve.call(null,G__20064));
} else {
return null;
}
}));
kuro.fs_cap_demo.bytes__GT_js = (function kuro$fs_cap_demo$bytes__GT_js(s){
return (new Uint8Array(Array.from(cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p1__20065_SHARP_){
return (p1__20065_SHARP_.charCodeAt() - (0));
}),s))));
});
kuro.fs_cap_demo.grant_read = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 1, ["fs/read:cid:greeting.txt",null], null), null);
kuro.fs_cap_demo.make_imports = (function kuro$fs_cap_demo$make_imports(grant,file_bytes,instance_ref){
return ({"kuro": ({"fs_read": (function (buf_ptr,len){
if(cljs.core.contains_QMARK_(grant,"fs/read:cid:greeting.txt")){
(new Uint8Array(cljs.core.deref(instance_ref).instance.exports.memory.buffer)).set(file_bytes,buf_ptr);

return len;
} else {
return (-1);
}
})})});
});
kuro.fs_cap_demo.run_guest = (function kuro$fs_cap_demo$run_guest(grant,file_bytes,instance_ref){
var g_imports = kuro.fs_cap_demo.make_imports(grant,file_bytes,instance_ref);
return WebAssembly.instantiateStreaming(fetch("/guest-fs.wasm"),g_imports).then((function (obj){
cljs.core.reset_BANG_(instance_ref,obj);

return Number((function (){var G__20067 = file_bytes.length;
var fexpr__20066 = (obj.instance.exports["run"]);
return (fexpr__20066.cljs$core$IFn$_invoke$arity$1 ? fexpr__20066.cljs$core$IFn$_invoke$arity$1(G__20067) : fexpr__20066.call(null,G__20067));
})());
}));
});
kuro.fs_cap_demo.read_side = (function kuro$fs_cap_demo$read_side(st1,cid,content){
var vec__20069 = kuro.fs.read_file(st1,"greeting.txt",(function (c){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(c,cid)){
return content;
} else {
return null;
}
}));
var st2 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20069,(0),null);
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20069,(1),null);
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"denials","denials",-392164446),cljs.core.count(cljs.core.filter.cljs$core$IFn$_invoke$arity$2((function (p1__20068_SHARP_){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword("kuro.fs","denied","kuro.fs/denied",-2058388799),new cljs.core.Keyword("kuro.fs","type","kuro.fs/type",2010862720).cljs$core$IFn$_invoke$arity$1(p1__20068_SHARP_));
}),kuro.fs.receipts(st2))),new cljs.core.Keyword(null,"receipts","receipts",-537015721),cljs.core.count(kuro.fs.receipts(st2))], null);
});
kuro.fs_cap_demo.run_cap_scenario = (function kuro$fs_cap_demo$run_cap_scenario(){
var st0 = kuro.fs.store.cljs$core$IFn$_invoke$arity$1("bafyrei-root");
var text_in = "guest reads via cap";
var content = kuro.fs_cap_demo.bytes__GT_js(text_in);
var expected_sum = cljs.core.reduce.cljs$core$IFn$_invoke$arity$3(cljs.core._PLUS_,(0),cljs.core.array_seq.cljs$core$IFn$_invoke$arity$1(content));
var instance_ref = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
return kuro.fs_cap_demo.call_worker(new cljs.core.Keyword(null,"put","put",1299772570),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"bytes","bytes",1175866680),content], null)).then((function (reply){
var cid = cljs.core.get.cljs$core$IFn$_invoke$arity$2(reply,"cid");
var vec__20072 = kuro.fs.write(st0,"greeting.txt",content,(function (_b){
return cid;
}));
var st1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20072,(0),null);
var _ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20072,(1),null);
return kuro.fs_cap_demo.call_worker(new cljs.core.Keyword(null,"get","get",1683182755),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"cid","cid",-1940591320),cid], null)).then((function (getr){
var file_bytes = cljs.core.get.cljs$core$IFn$_invoke$arity$2(getr,"bytes");
return kuro.fs_cap_demo.run_guest(kuro.fs_cap_demo.grant_read,file_bytes,instance_ref).then((function (granted_sum){
return kuro.fs_cap_demo.run_guest(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 1, ["fs/write:cid:greeting.txt",null], null), null),file_bytes,instance_ref).then((function (denied_sum){
var side = kuro.fs_cap_demo.read_side(st1,cid,content);
return ({"guestRead": (granted_sum > (0)), "checksumMatch": cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(granted_sum,expected_sum), "writeDenied": (denied_sum === (0)), "fsDenials": new cljs.core.Keyword(null,"denials","denials",-392164446).cljs$core$IFn$_invoke$arity$1(side), "cid": cid});
}));
}));
}));
}));
});
(window.fsCapE2E = (function (){
return kuro.fs_cap_demo.run_cap_scenario().then((function (results){
(window.__capResults = results);

return results;
}));
}));

//# sourceMappingURL=kuro.fs_cap_demo.js.map
