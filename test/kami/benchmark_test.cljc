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
