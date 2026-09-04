(module
  (memory (export "memory") 1)
  (func (export "run") (param $a i32) (param $b i32) (result i32)
    (local $sum i32)
    (local.set $sum (i32.add (local.get $a) (local.get $b)))
    ;; write "sum=<n>" into memory at 1024 for the host to read
    (i32.store8 (i32.const 1024) (i32.const 115)) ;; s
    (i32.store8 (i32.const 1025) (i32.const 117)) ;; u
    (i32.store8 (i32.const 1026) (i32.const 109)) ;; m
    (i32.store8 (i32.const 1027) (i32.const 61))  ;; =
    ;; decimal digits of sum (small: fits in 2 digits for test)
    (i32.store8 (i32.const 1028) (i32.add (i32.const 48)
                 (i32.div_u (local.get $sum) (i32.const 10))))
    (i32.store8 (i32.const 1029) (i32.add (i32.const 48)
                 (i32.rem_u (local.get $sum) (i32.const 10))))
    (local.get $sum)))
