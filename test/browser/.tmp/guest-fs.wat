(module
  (import "kuro" "fs_read"
    (func $fs_read (param i32 i32) (result i32)))
  (memory (export "memory") 1)
  (func (export "run") (param $len i32) (result i32)
    ;; ask the host to copy the file into guest memory at 2048
    (local $i i32) (local $sum i32)
    (drop (call $fs_read (i32.const 2048) (local.get $len)))
    ;; checksum: sum of bytes the host copied
    (local.set $i (i32.const 0))
    (block $done
      (loop $next
        (br_if $done (i32.ge_u (local.get $i) (local.get $len)))
        (local.set $sum (i32.add (local.get $sum)
                                 (i32.load8_u (i32.add (i32.const 2048) (local.get $i)))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $next)))
    (local.get $sum)))
