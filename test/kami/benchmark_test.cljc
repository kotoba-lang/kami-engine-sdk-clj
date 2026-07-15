(ns kami.benchmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [kami.benchmark :as benchmark]))

(def manifest
  {:sample/id :isekai/swarm
   :sample/title "Isekai Swarm"
   :sample/dimension :2d
   :sample/genre :action-roguelite
   :sample/metrics [:frame/fps :frame/cpu-ms :frame/gpu-ms :frame/p95-ms
                    :render/visible-entities :render/particles]
   :sample/tiers
   {:playable {:target-fps 30 :budget {:render/visible-entities 5000}}
    :showcase {:target-fps 60 :budget {:render/visible-entities 10000}}
    :meltdown {:target-fps 60
               :budget {:render/visible-entities 100000}
               :load {:unit :entities :initial 10000 :step 5000 :maximum 100000}}}})

(deftest manifest-contract
  (is (true? (benchmark/valid-manifest? manifest)))
  (is (= 60 (:target-fps (benchmark/quality-config manifest :showcase))))
  (testing "every standard tier is mandatory"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (benchmark/valid-manifest? (update manifest :sample/tiers dissoc :playable)))))
  (testing "metrics must use the common vocabulary"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (benchmark/valid-manifest? (update manifest :sample/metrics conj :fps))))))

(deftest telemetry-contract
  (let [frame (benchmark/telemetry-frame
               manifest :showcase 42 1234.5
               {:frame/fps 60.0 :frame/cpu-ms 3.1 :frame/gpu-ms 7.4
                :render/visible-entities 9999})]
    (is (= benchmark/contract-version (:telemetry/contract frame)))
    (is (= :isekai/swarm (:telemetry/sample frame)))
    (is (= :showcase (:telemetry/tier frame))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (benchmark/telemetry-frame manifest :showcase 0 0 {:memory/rss 1}))))

(deftest meltdown-ramp
  (is (= 10000 (benchmark/next-meltdown-load manifest nil)))
  (is (= 15000 (benchmark/next-meltdown-load manifest 10000)))
  (is (= 100000 (benchmark/next-meltdown-load manifest 99000))))

(def performance-plan
  {:schema "kami.performance-plan/v1" :dimension "3d" :workload "mesh-density"
   :samples [{:entities 100 :durationMs 5000 :warmupFrames 60}
             {:entities 200 :durationMs 5000 :warmupFrames 60}
             {:entities 300 :durationMs 5000 :warmupFrames 60}]
   :budgets {:frameTimeP95Ms 16.7 :memoryMaxMiB 512}
   :stopOnFirstViolation true})

(deftest portable-performance-plan-contract
  (is (true? (benchmark/valid-performance-plan? performance-plan)))
  (is (= 15.0 (benchmark/percentile [1.0 15.0 8.0 4.0] 0.95)))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (benchmark/valid-performance-plan?
                (assoc performance-plan :dimension "xr")))))

(deftest saturation-runner-stops-at-first-violation
  (let [attempts (atom [])
        result (benchmark/run-saturation
                performance-plan
                (fn [{:keys [entities]}]
                  (swap! attempts conj entities)
                  {:frame-times-ms (if (= 200 entities) [10.0 18.0] [8.0 9.0])
                   :memory-max-mib 256}))]
    (is (= [100 200] @attempts))
    (is (= :violated (:status result)))
    (is (= 100 (:saturationEntities result)))
    (is (= [:frame-time-p95] (-> result :results last :violations)))))

(deftest saturation-runner-reports-the-tested-ceiling
  (let [result (benchmark/run-saturation
                (assoc performance-plan :dimension "2d")
                (constantly {:frame-times-ms [5.0 6.0] :memory-max-mib 64}))]
    (is (= :passed (:status result)))
    (is (= 300 (:saturationEntities result)))
    (is (= 3 (count (:results result))))))

(def scenario
  {:scenario/id :isekai-swarm/reduced-ci
   :scenario/seed 7
   :scenario/ticks 4
   :scenario/fixed-step-ms 16.666667
   :scenario/inputs [{:tick 1 :action :spawn :value 3}
                     {:tick 2 :action :damage :value 1}]})

(defn fixture-step [state {:keys [tick dt-ms inputs]}]
  (reduce (fn [s {:keys [action value]}]
            (case action
              :spawn (update s :entities + value)
              :damage (update s :health - value)))
          (assoc state :last-tick tick :elapsed-ms (+ (:elapsed-ms state) dt-ms))
          inputs))

(deftest deterministic-headless-contract
  (is (true? (benchmark/valid-scenario? scenario)))
  (let [run #(benchmark/run-headless
              scenario
              (fn [seed] {:seed seed :entities 1 :health 10 :elapsed-ms 0.0})
              fixture-step)
        first-run (run)
        second-run (run)]
    (is (= (:headless/final-state first-run)
           {:seed 7 :entities 4 :health 9 :elapsed-ms 66.666668 :last-tick 3}))
    (is (= (:headless/simulation-digest first-run)
           (:headless/simulation-digest second-run)))
    (is (= (benchmark/deterministic-digest {:b #{3 2} :a 1})
           (benchmark/deterministic-digest {:a 1 :b #{2 3}}))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (benchmark/valid-scenario?
                (assoc-in scenario [:scenario/inputs 0 :tick] 4)))))

(def baseline
  {:result/sample :isekai/swarm
   :result/scenario :isekai-swarm/reduced-ci
   :result/tier :showcase
   :result/build "main@abc"
   :result/profile :ci/headless-jvm
   :result/measurements {:frame/fps 60.0 :frame/p95-ms 10.0}
   :result/simulation-digest 123})

(deftest baseline-comparison-contract
  (let [candidate (-> baseline
                      (assoc :result/build "pr@def")
                      (assoc :result/measurements
                             {:frame/fps 58.0 :frame/p95-ms 10.9}))
        comparison (benchmark/compare-results
                    manifest baseline candidate
                    {:frame/fps 0.1 :frame/p95-ms 0.1}
                    #{:frame/fps})]
    (is (true? (benchmark/valid-result? manifest candidate)))
    (is (:comparison/pass? comparison))
    (is (< (abs (- 0.09 (get-in comparison
                                [:comparison/metrics :frame/p95-ms
                                 :regression-ratio])))
           1.0e-9)))
  (testing "a >10% p95 regression fails"
    (let [candidate (assoc baseline :result/measurements
                           {:frame/fps 60.0 :frame/p95-ms 11.1})]
      (is (false? (:comparison/pass?
                   (benchmark/compare-results
                    manifest baseline candidate {:frame/p95-ms 0.1} #{}))))))
  (testing "digest mismatch fails even when performance passes"
    (let [candidate (assoc baseline :result/simulation-digest 124)]
      (is (false? (:comparison/pass?
                   (benchmark/compare-results
                    manifest baseline candidate {:frame/p95-ms 0.1} #{})))))))
