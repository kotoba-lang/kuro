(ns kuro.ansi-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kuro.ansi :as ansi]))

(def ^:private e "\u001b")

(deftest strips-escapes-from-plain-text
  (is (= "ok" (ansi/plain (str e "[32mok" e "[0m"))))
  (testing "sequences we do not interpret still must not reach the screen"
    (is (= "ab" (ansi/plain (str "a" e "[2Kb"))))          ; erase line
    (is (= "ab" (ansi/plain (str "a" e "[1;1Hb"))))        ; cursor position
    (is (= "ab" (ansi/plain (str "a" e "]0;title" e "\\" "b"))))  ; OSC + ST
    (is (= "ab" (ansi/plain (str "a" e "]0;title\u0007b"))))   ; OSC + BEL
    (is (= "ab" (ansi/plain (str "a" e "(Bb"))))))         ; charset select

(deftest private-csi-and-scroll-regions-are-discarded
  (testing "README: 'cursor addressing, scroll regions, alternate screen — is
            discarded, never printed'. Private-mode CSI (the `?` prefix) is
            what real programs emit: `?25h/l` hide the cursor, `?1049h/l`
            enter the alternate screen. `?` is 0x3f, below the 0x40-0x7e
            final-byte window, so it must be read as a parameter — not left
            in the body and not treated as text."
    (is (= "ab" (ansi/plain (str "a" e "[?25lb"))))
    (is (= "ab" (ansi/plain (str "a" e "[?1049h" e "[?1049l" "b")))))
  (testing "scroll region (DECSTBM) and hide/show cursor around real text"
    (is (= [[{:text "start" :style {}}] [{:text "end" :style {}}]]
           (ansi/lines (str e "[2;5rstart\n" e "[?25h" "end" e "[?25l")))
        "the text survives; the sequences around it are gone"))
  (testing "an SGR still applies after a discarded private CSI"
    (is (= [{:text "ok" :style {:fg "green"}}]
           (ansi/spans (str e "[?1049h" e "[32mok" e "[?1049l"))))))

(deftest colors
  (is (= [{:text "ok" :style {:fg "green"}}]
         (ansi/spans (str e "[32mok"))))
  (is (= {:fg "bright-red" :bg "blue" :bold true}
         (:style (first (ansi/spans (str e "[91;44;1mx"))))))
  (testing "256-colour and truecolor"
    (is (= {:fg {:index 214}} (:style (first (ansi/spans (str e "[38;5;214mx"))))))
    (is (= {:bg {:rgb [10 20 30]}} (:style (first (ansi/spans (str e "[48;2;10;20;30mx")))))))
  (testing "reset clears everything, and 39/49 clear only one channel"
    (is (= {} (ansi/apply-sgr {:fg "red" :bold true} [0])))
    (is (= {:bold true} (ansi/apply-sgr {:fg "red" :bold true} [39])))
    (is (= {:fg "red"} (ansi/apply-sgr {:fg "red" :bold true} [22]))))
  (testing "CSI m with no parameters is a reset (ECMA-48: omitted parameter = 0)"
    (is (= [{:text "x" :style {}}] (ansi/spans (str e "[31m" e "[mx"))))))

(deftest adjacent-same-style-is-one-span
  (is (= [{:text "abc" :style {:fg "red"}}]
         (ansi/spans (str e "[31ma" e "[31mb" e "[31mc")))
      "a redundant repeat of the same SGR must not fragment the output"))

(deftest broken-escapes-do-not-throw
  (testing "a truncated log is normal; it must not take the whole render down"
    (is (= "a" (ansi/plain (str "a" e "[31"))))       ; CSI with no final byte
    (is (= "a" (ansi/plain (str "a" e))))             ; lone ESC at EOF
    (is (= "x" (ansi/plain (str e "[38;5mx"))))       ; 256-colour missing index
    (is (= "x" (ansi/plain (str e "[38;2;1mx"))))))   ; truecolor missing channels

(deftest carriage-return-overwrites-in-place
  (testing "a shorter overwrite leaves the tail, exactly like a real terminal"
    (is (= [[{:text "abXde" :style {}}]] (ansi/lines "abcde\rabX")))
    (is (= [[{:text "doneress" :style {}}]] (ansi/lines "progress\rdone"))
        "\\r alone does NOT clear the line — this residue is what a real tty shows"))
  (testing "\\r at end of line does not eat the line"
    (is (= [[{:text "hello" :style {}}] []]
           (ansi/lines "hello\r\n")))))

(deftest erase-in-line-collapses-progress-bars
  (testing "CSI K after CR is how a progress bar actually becomes one line"
    (is (= [[{:text "done" :style {}}]]
           (ansi/lines (str "progress\rdone" e "[K")))))
  (testing "the three erase modes"
    (is (= [[{:text "ab" :style {}}]] (ansi/lines (str "abcde\r\r" e "[2Kab")))  )
    (is (= [[{:text "abc" :style {}}]] (ansi/lines (str "abcde\rabc" e "[0K")))
        "mode 0 (the default) erases from the cursor to end of line")
    (is (= [[{:text "   de" :style {}}]] (ansi/lines (str "abcde\rab" e "[1K")))
        "mode 1 erases from line start through the cursor"))
  (testing "a realistic download bar: repeated redraws collapse to the last one"
    (is (= [[{:text "100% complete" :style {}}]]
           (ansi/lines (str "\r 10% ..........." e "[K"
                            "\r 55% ......." e "[K"
                            "\r100% complete" e "[K"))))))

(deftest backspace-and-tabs
  (is (= [[{:text "ac" :style {}}]] (ansi/lines "ab\bc")))
  (is (= [[{:text "a       b" :style {}}]] (ansi/lines "a\tb")))
  (is (= [[{:text "a b" :style {}}]] (ansi/lines "a\tb" {:tab-width 2}))))

(deftest lines-split-and-styles-persist-across-newlines
  (let [ls (ansi/lines (str e "[32mone\ntwo" e "[0m\nthree"))]
    (is (= 3 (count ls)))
    (is (= "one" (:text (ffirst ls))))
    (is (= {:fg "green"} (:style (ffirst ls))))
    (is (= {:fg "green"} (:style (first (second ls))))
        "SGR state carries over a newline, as on a real terminal")
    (is (= [{:text "three" :style {}}] (nth ls 2)))))

(deftest truncate-says-how-much-it-dropped
  (let [ls (ansi/lines (str/join "\n" (map str (range 100))))]
    (is (= 100 (count ls)))
    (let [[kept dropped] (ansi/truncate-lines ls 10)]
      (is (= 10 (count kept)))
      (is (= 90 dropped)))
    (testing "no truncation reports zero, not nil"
      (is (= [ls 0] (ansi/truncate-lines ls 1000))))))

(deftest real-world-output
  (testing "git-style coloured status survives intact"
    (let [out (str e "[32mM " e "[0msrc/kuro/ansi.cljc\n"
               e "[31mD " e "[0mold.cljc\n")
          ls (ansi/lines out)]
      (is (= 3 (count ls)))                     ; trailing newline -> empty last line
      (is (= "M src/kuro/ansi.cljc" (str/join (map :text (first ls)))))
      (is (= {:fg "green"} (:style (ffirst ls))))
      (is (= {} (:style (second (first ls))))))))
