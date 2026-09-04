goog.provide('kuro.fs');
/**
 * Length of a byte container on either runtime. JVM byte-arrays use alength;
 *   JS typed arrays use .length.
 */
kuro.fs.byte_count = (function kuro$fs$byte_count(b){
return b.length;
});
/**
 * True for a byte container on either runtime. JVM: byte[]; cljs: typed
 *   array. Named byte-seq? (not bytes?) because clojure.core/bytes? exists on
 *   the JVM and a local def would shadow it.
 */
kuro.fs.byte_seq_QMARK_ = (function kuro$fs$byte_seq_QMARK_(b){
return (b instanceof Uint8Array);
});
kuro.fs.format_version = (1);
/**
 * The capability vocabulary `wc.fs` understands. A host session maps its own
 *   grant onto these; anything not granted produces a denial receipt.
 */
kuro.fs.default_capabilities = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 5, ["fs/publish",null,"fs/ls",null,"fs/write",null,"fs/rm",null,"fs/read",null], null), null);
/**
 * Normalize a POSIX-ish path to a vector of segments, refusing escapes.
 *   Returns nil (do not throw — callers convert to a denial receipt) when the
 *   path is not a string, is blank, or contains a `..` traversal. `.` segments
 *   are dropped, so `.` and `./` resolve to the root (empty vector, truthy).
 */
kuro.fs.clean_path = (function kuro$fs$clean_path(p){
if(((typeof p === 'string') && ((!(clojure.string.blank_QMARK_(p)))))){
var segs = cljs.core.remove.cljs$core$IFn$_invoke$arity$2((function (p1__21653_SHARP_){
return ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("",p1__21653_SHARP_)) || (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(".",p1__21653_SHARP_)));
}),clojure.string.split.cljs$core$IFn$_invoke$arity$2(p,/\/+/));
if(cljs.core.not(cljs.core.some((function (p1__21654_SHARP_){
return cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("..",p1__21654_SHARP_);
}),segs))){
return cljs.core.vec(segs);
} else {
return null;
}
} else {
return null;
}
});
kuro.fs.join_path = (function kuro$fs$join_path(segs){
return clojure.string.join.cljs$core$IFn$_invoke$arity$2("/",segs);
});
kuro.fs.new_store = (function kuro$fs$new_store(mount_root){
return new cljs.core.PersistentArrayMap(null, 5, [new cljs.core.Keyword("kuro.fs","format-version","kuro.fs/format-version",1316368300),kuro.fs.format_version,new cljs.core.Keyword("kuro.fs","mount-root","kuro.fs/mount-root",1088936078),mount_root,new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735),cljs.core.PersistentArrayMap.EMPTY,new cljs.core.Keyword("kuro.fs","root","kuro.fs/root",1474145999),new cljs.core.Keyword(null,"root","root",-448657453),new cljs.core.Keyword("kuro.fs","receipts","kuro.fs/receipts",475231579),cljs.core.PersistentVector.EMPTY], null);
});
/**
 * A fresh filesystem mounted at `mount-root` (a repo-root CID string).
 */
kuro.fs.store = (function kuro$fs$store(var_args){
var G__21662 = arguments.length;
switch (G__21662) {
case 0:
return kuro.fs.store.cljs$core$IFn$_invoke$arity$0();

break;
case 1:
return kuro.fs.store.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(kuro.fs.store.cljs$core$IFn$_invoke$arity$0 = (function (){
return kuro.fs.store.cljs$core$IFn$_invoke$arity$1(null);
}));

(kuro.fs.store.cljs$core$IFn$_invoke$arity$1 = (function (mount_root){
return kuro.fs.new_store(mount_root);
}));

(kuro.fs.store.cljs$lang$maxFixedArity = 1);

/**
 * Append a receipt to the store value. Takes and returns the store.
 */
kuro.fs.record_BANG_ = (function kuro$fs$record_BANG_(st,receipt){
return cljs.core.update.cljs$core$IFn$_invoke$arity$4(st,new cljs.core.Keyword("kuro.fs","receipts","kuro.fs/receipts",475231579),cljs.core.conj,receipt);
});
/**
 * Build (and record) a denial receipt, and return `[store' nil]` — the same
 *   shape the public API returns on success, so every caller destructures one
 *   way. `extra` must be a map of fixed keys.
 */
kuro.fs.denied = (function kuro$fs$denied(var_args){
var G__21668 = arguments.length;
switch (G__21668) {
case 4:
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
case 5:
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$5((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(kuro.fs.denied.cljs$core$IFn$_invoke$arity$4 = (function (st,op,path,reason){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$5(st,op,path,reason,cljs.core.PersistentArrayMap.EMPTY);
}));

(kuro.fs.denied.cljs$core$IFn$_invoke$arity$5 = (function (st,op,path,reason,extra){
var r = cljs.core.merge.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword("kuro.fs","type","kuro.fs/type",2010862720),new cljs.core.Keyword("kuro.fs","denied","kuro.fs/denied",-2058388799),new cljs.core.Keyword("kuro.fs","op","kuro.fs/op",761483097),op,new cljs.core.Keyword("kuro.fs","path","kuro.fs/path",174901588),((typeof path === 'string')?path:kuro.fs.join_path(path)),new cljs.core.Keyword("kuro.fs","reason","kuro.fs/reason",1908354285),reason], null),cljs.core.select_keys(extra,new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword("kuro.fs","missing","kuro.fs/missing",-552150267)], null))], 0));
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [kuro.fs.record_BANG_(st,r),null], null);
}));

(kuro.fs.denied.cljs$lang$maxFixedArity = 5);

kuro.fs.ok_receipt = (function kuro$fs$ok_receipt(op,path,extra){
return cljs.core.merge.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword("kuro.fs","type","kuro.fs/type",2010862720),new cljs.core.Keyword("kuro.fs","receipt","kuro.fs/receipt",-567549011),new cljs.core.Keyword("kuro.fs","op","kuro.fs/op",761483097),op,new cljs.core.Keyword("kuro.fs","path","kuro.fs/path",174901588),((typeof path === 'string')?path:kuro.fs.join_path(path))], null),cljs.core.select_keys(extra,new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword("kuro.fs","cid","kuro.fs/cid",-1305385428),new cljs.core.Keyword("kuro.fs","size","kuro.fs/size",890773155),new cljs.core.Keyword("kuro.fs","entries","kuro.fs/entries",-732040901),new cljs.core.Keyword("kuro.fs","at-ms","kuro.fs/at-ms",-992189253),new cljs.core.Keyword("kuro.fs","root-cid","kuro.fs/root-cid",1256195604)], null))], 0));
});
/**
 * Resolve a path to a node (or nil). Read-only.
 */
kuro.fs.node_at = (function kuro$fs$node_at(nodes,segs){
var id = new cljs.core.Keyword(null,"root","root",-448657453);
var G__21676 = segs;
var vec__21677 = G__21676;
var seq__21678 = cljs.core.seq(vec__21677);
var first__21679 = cljs.core.first(seq__21678);
var seq__21678__$1 = cljs.core.next(seq__21678);
var seg = first__21679;
var rest = seq__21678__$1;
var id__$1 = id;
var G__21676__$1 = G__21676;
while(true){
var id__$2 = id__$1;
var vec__21683 = G__21676__$1;
var seq__21684 = cljs.core.seq(vec__21683);
var first__21685 = cljs.core.first(seq__21684);
var seq__21684__$1 = cljs.core.next(seq__21684);
var seg__$1 = first__21685;
var rest__$1 = seq__21684__$1;
var n = cljs.core.get.cljs$core$IFn$_invoke$arity$2(nodes,id__$2);
if((n == null)){
return null;
} else {
if((seg__$1 == null)){
return n;
} else {
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(n))){
return null;
} else {
var G__21746 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(n),seg__$1);
var G__21747 = rest__$1;
id__$1 = G__21746;
G__21676__$1 = G__21747;
continue;

}
}
}
break;
}
});
kuro.fs.dir_with = (function kuro$fs$dir_with(entries){
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"entries","entries",-86943161),entries], null);
});
kuro.fs.put_node = (function kuro$fs$put_node(nodes,id,node){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(nodes,id,node);
});
/**
 * Set `node` at `segs`, creating intermediate directories. Returns
 *   [nodes' node-id] on success, or nil when a file blocks the path.
 * 
 *   Two-phase: assign the leaf node first, then link it from every ancestor up
 *   to :root. The single-segment case (`hello.txt`) must still link from :root,
 *   which is why the leaf assignment happens before the ancestor walk rather
 *   than being returned early. The ancestor walk's accumulated nodes map is the
 *   returned nodes' — the leaf-only map is never used on its own.
 */
kuro.fs.set_path = (function kuro$fs$set_path(nodes,segs,node){
var leaf_id = cljs.core.keyword.cljs$core$IFn$_invoke$arity$1((""+"n"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.count(cljs.core.keys(nodes)))+"-"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(kuro.fs.join_path(segs))));
var link = (function kuro$fs$set_path_$_link(acc,child_id,rev){
while(true){
if(cljs.core.empty_QMARK_(rev)){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [acc,child_id], null);
} else {
var seg = cljs.core.first(rev);
var parent_segs = cljs.core.vec(cljs.core.reverse(cljs.core.rest(rev)));
if(cljs.core.empty_QMARK_(parent_segs)){
var pn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(acc,new cljs.core.Keyword(null,"root","root",-448657453));
var entries = (function (){var or__5162__auto__ = new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(pn);
if(cljs.core.truth_(or__5162__auto__)){
return or__5162__auto__;
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
})();
var G__21748 = kuro.fs.put_node(acc,new cljs.core.Keyword(null,"root","root",-448657453),kuro.fs.dir_with(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(entries,seg,child_id)));
var G__21749 = new cljs.core.Keyword(null,"root","root",-448657453);
var G__21750 = cljs.core.rest(rev);
acc = G__21748;
child_id = G__21749;
rev = G__21750;
continue;
} else {
var pn = kuro.fs.node_at(acc,parent_segs);
if((pn == null)){
var pid = cljs.core.keyword.cljs$core$IFn$_invoke$arity$1((""+"n"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.count(cljs.core.keys(acc)))+"-"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(kuro.fs.join_path(parent_segs))));
var G__21751 = kuro.fs.put_node(acc,pid,kuro.fs.dir_with(cljs.core.PersistentArrayMap.createAsIfByAssoc([seg,child_id])));
var G__21752 = pid;
var G__21753 = cljs.core.rest(rev);
acc = G__21751;
child_id = G__21752;
rev = G__21753;
continue;
} else {
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(pn))){
return null;
} else {
var pid = cljs.core.keyword.cljs$core$IFn$_invoke$arity$1((""+"n"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.count(cljs.core.keys(acc)))+"-"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(kuro.fs.join_path(parent_segs))));
var G__21754 = kuro.fs.put_node(acc,pid,kuro.fs.dir_with(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3((function (){var or__5162__auto__ = new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(pn);
if(cljs.core.truth_(or__5162__auto__)){
return or__5162__auto__;
} else {
return cljs.core.PersistentArrayMap.EMPTY;
}
})(),seg,child_id)));
var G__21755 = pid;
var G__21756 = cljs.core.rest(rev);
acc = G__21754;
child_id = G__21755;
rev = G__21756;
continue;

}
}
}
}
break;
}
});
var temp__5825__auto__ = link(kuro.fs.put_node(nodes,leaf_id,node),leaf_id,cljs.core.reverse(segs));
if(cljs.core.truth_(temp__5825__auto__)){
var linked = temp__5825__auto__;
return linked;
} else {
return null;
}
});
/**
 * Remove `segs` (a file or empty dir). Returns updated nodes, or nil when the
 *   path does not exist.
 */
kuro.fs.remove_path = (function kuro$fs$remove_path(nodes,segs){
var parent_segs = cljs.core.butlast(segs);
var name = cljs.core.last(segs);
var pn = kuro.fs.node_at(nodes,(function (){var or__5162__auto__ = parent_segs;
if(or__5162__auto__){
return or__5162__auto__;
} else {
return cljs.core.PersistentVector.EMPTY;
}
})());
if(cljs.core.truth_((function (){var and__5160__auto__ = pn;
if(cljs.core.truth_(and__5160__auto__)){
return ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(pn))) && (cljs.core.contains_QMARK_(new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(pn),name)));
} else {
return and__5160__auto__;
}
})())){
if(cljs.core.empty_QMARK_(parent_segs)){
return kuro.fs.put_node(nodes,new cljs.core.Keyword(null,"root","root",-448657453),kuro.fs.dir_with(cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(pn),name)));
} else {
var id = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(kuro.fs.node_at(nodes,cljs.core.butlast(parent_segs)),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"entries","entries",-86943161),cljs.core.last(parent_segs)], null));
return kuro.fs.put_node(nodes,id,kuro.fs.dir_with(cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(pn),name)));
}
} else {
return null;
}
});
/**
 * List a directory. Returns [store' entries] where entries is
 *   [{:name :type :size} ...] sorted by name; a denial records a receipt and
 *   returns [store' nil].
 */
kuro.fs.ls = (function kuro$fs$ls(st,path){
var op = new cljs.core.Keyword(null,"ls","ls",1195788590);
var segs = kuro.fs.clean_path(path);
if((segs == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"bad-path","bad-path",-679452546));
} else {
var n = kuro.fs.node_at(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs);
if((n == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"not-found","not-found",-629079980));
} else {
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(n))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"not-a-directory","not-a-directory",242984332));
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [kuro.fs.record_BANG_(st,kuro.fs.ok_receipt(op,path,new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword("kuro.fs","entries","kuro.fs/entries",-732040901),cljs.core.count(new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(n))], null))),cljs.core.vec(cljs.core.sort_by.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"name","name",1843675177),cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p__21704){
var vec__21705 = p__21704;
var name = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__21705,(0),null);
var child_id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__21705,(1),null);
var cn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),child_id);
return new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"name","name",1843675177),name,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(cn),new cljs.core.Keyword(null,"size","size",1098693007),(function (){var or__5162__auto__ = new cljs.core.Keyword(null,"size","size",1098693007).cljs$core$IFn$_invoke$arity$1(cn);
if(cljs.core.truth_(or__5162__auto__)){
return or__5162__auto__;
} else {
return (0);
}
})()], null);
}),new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(n))))], null);

}
}

}
});
/**
 * Read a file's bytes via the host's block store. `get-block` is injected:
 *   cid -> bytes | nil. Returns [store' bytes] — bytes nil on denial.
 */
kuro.fs.read_file = (function kuro$fs$read_file(st,path,get_block){
var op = new cljs.core.Keyword(null,"read","read",1140058661);
var segs = kuro.fs.clean_path(path);
if((segs == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"bad-path","bad-path",-679452546));
} else {
var n = kuro.fs.node_at(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs);
if((n == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"not-found","not-found",-629079980));
} else {
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"file","file",-1269645878),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(n))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"not-a-file","not-a-file",-689219580));
} else {
var bytes = (function (){var G__21729 = new cljs.core.Keyword(null,"cid","cid",-1940591320).cljs$core$IFn$_invoke$arity$1(n);
return (get_block.cljs$core$IFn$_invoke$arity$1 ? get_block.cljs$core$IFn$_invoke$arity$1(G__21729) : get_block.call(null,G__21729));
})();
if((bytes == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$5(st,op,path,new cljs.core.Keyword(null,"block-missing","block-missing",-57515908),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword("kuro.fs","cid","kuro.fs/cid",-1305385428),new cljs.core.Keyword(null,"cid","cid",-1940591320).cljs$core$IFn$_invoke$arity$1(n)], null));
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [kuro.fs.record_BANG_(st,kuro.fs.ok_receipt(op,path,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword("kuro.fs","cid","kuro.fs/cid",-1305385428),new cljs.core.Keyword(null,"cid","cid",-1940591320).cljs$core$IFn$_invoke$arity$1(n),new cljs.core.Keyword("kuro.fs","size","kuro.fs/size",890773155),new cljs.core.Keyword(null,"size","size",1098693007).cljs$core$IFn$_invoke$arity$1(n)], null))),bytes], null);
}

}
}

}
});
/**
 * Write bytes to a path. `put-block` is injected: bytes -> cid (host computes
 *   the unixfs DAG and stores it). Returns [store' cid] — cid nil on denial.
 *   Writing over a directory is refused; over a file replaces it.
 */
kuro.fs.write = (function kuro$fs$write(st,path,bytes,put_block){
var op = new cljs.core.Keyword(null,"write","write",-1857649168);
var segs = kuro.fs.clean_path(path);
if((segs == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"bad-path","bad-path",-679452546));
} else {
if((!(kuro.fs.byte_seq_QMARK_(bytes)))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"bytes-required","bytes-required",-1387374648));
} else {
var n = kuro.fs.node_at(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(n))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"is-a-directory","is-a-directory",-662560190));
} else {
var cid = (put_block.cljs$core$IFn$_invoke$arity$1 ? put_block.cljs$core$IFn$_invoke$arity$1(bytes) : put_block.call(null,bytes));
var setp = kuro.fs.set_path(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"file","file",-1269645878),new cljs.core.Keyword(null,"cid","cid",-1940591320),cid,new cljs.core.Keyword(null,"size","size",1098693007),kuro.fs.byte_count(bytes)], null));
var nodes = cljs.core.first(setp);
if((((setp == null)) || ((nodes == null)))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"path-blocked","path-blocked",1409915119));
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [kuro.fs.record_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(st,new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735),nodes),kuro.fs.ok_receipt(op,path,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword("kuro.fs","cid","kuro.fs/cid",-1305385428),cid,new cljs.core.Keyword("kuro.fs","size","kuro.fs/size",890773155),kuro.fs.byte_count(bytes)], null))),cid], null);
}

}

}
}
});
/**
 * Create a directory (and missing parents). Returns [store' path] on success.
 */
kuro.fs.mkdir = (function kuro$fs$mkdir(st,path){
var op = new cljs.core.Keyword(null,"mkdir","mkdir",-1619505530);
var segs = kuro.fs.clean_path(path);
if((segs == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"bad-path","bad-path",-679452546));
} else {
var setp = kuro.fs.set_path(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs,kuro.fs.dir_with(cljs.core.PersistentArrayMap.EMPTY));
var nodes = cljs.core.first(setp);
if((((setp == null)) || ((nodes == null)))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"path-blocked","path-blocked",1409915119));
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [kuro.fs.record_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(st,new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735),nodes),kuro.fs.ok_receipt(op,path,cljs.core.PersistentArrayMap.EMPTY)),path], null);
}

}
});
/**
 * Remove a file or an empty directory. Non-empty directories are refused —
 *   a recursive remove is a separate, explicit decision.
 */
kuro.fs.rm = (function kuro$fs$rm(st,path){
var op = new cljs.core.Keyword(null,"rm","rm",-1641953697);
var segs = kuro.fs.clean_path(path);
if((segs == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"bad-path","bad-path",-679452546));
} else {
var n = kuro.fs.node_at(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs);
if((n == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"not-found","not-found",-629079980));
} else {
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"dir","dir",1734754661),new cljs.core.Keyword(null,"type","type",1174270348).cljs$core$IFn$_invoke$arity$1(n))) && (cljs.core.seq(new cljs.core.Keyword(null,"entries","entries",-86943161).cljs$core$IFn$_invoke$arity$1(n))))){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"directory-not-empty","directory-not-empty",-247575287));
} else {
var nodes = kuro.fs.remove_path(new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735).cljs$core$IFn$_invoke$arity$1(st),segs);
if((nodes == null)){
return kuro.fs.denied.cljs$core$IFn$_invoke$arity$4(st,op,path,new cljs.core.Keyword(null,"not-found","not-found",-629079980));
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [kuro.fs.record_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(st,new cljs.core.Keyword("kuro.fs","nodes","kuro.fs/nodes",1887925735),nodes),kuro.fs.ok_receipt(op,path,cljs.core.PersistentArrayMap.EMPTY)),true], null);
}

}
}

}
});
/**
 * All receipts so far — accepted AND denied. An audit reads both.
 */
kuro.fs.receipts = (function kuro$fs$receipts(st){
return new cljs.core.Keyword("kuro.fs","receipts","kuro.fs/receipts",475231579).cljs$core$IFn$_invoke$arity$1(st);
});

//# sourceMappingURL=kuro.fs.js.map
