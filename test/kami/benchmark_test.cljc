(ns kami.benchmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [kami.benchmark :as benchmark]))

(def manifest
  {:sample/id :isekai/swarm
   :sample/title "Isekai Swarm"
   :sample/dimension :2d
   :sample/genre :action-roguelite
   :sample/metrics [:frame/fps :frame/cpu-ms :frame/gpu-ms
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
