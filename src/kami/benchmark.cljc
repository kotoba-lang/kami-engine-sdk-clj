(ns kami.benchmark
  "Portable benchmark contract for KAMI sample games.

  A sample manifest declares genre, dimensions, supported quality tiers and
  measurable budgets.  Runtime telemetry uses the same keyword vocabulary on
  browser, native and headless hosts, making recordings comparable and safe to
  persist as EDN.  This namespace intentionally performs no clock or GPU calls."
  (:require [clojure.set :as set]))

(def contract-version 1)
(def quality-tiers [:playable :showcase :meltdown])

(def required-manifest-keys
  #{:sample/id :sample/title :sample/dimension :sample/genre
    :sample/tiers :sample/metrics})

(def dimensions #{:2d :3d})

(def metric-units
  {:frame/fps :hz
   :frame/cpu-ms :ms
   :frame/gpu-ms :ms
   :frame/p95-ms :ms
   :frame/p99-ms :ms
   :render/draw-calls :count
   :render/dispatch-calls :count
   :render/triangles :count
   :render/visible-entities :count
   :render/particles :count
   :memory/estimated-vram-mb :mb
   :asset/stream-bytes-sec :bytes-sec
   :network/rtt-ms :ms
   :network/jitter-ms :ms
   :network/packet-loss :ratio
   :network/rollback-count :count
   :script/cpu-ms :ms
   :script/gc-pause-ms :ms})

(defn- fail [message data]
  (throw (ex-info (str "benchmark: " message) data)))

(defn valid-manifest?
  "Validate a sample manifest and return true. Throws with useful ex-data.

  Tier values are maps whose optional :budget map uses metrics declared in
  :sample/metrics. :meltdown may additionally define :load with :initial,
  :step and :maximum positive numeric values."
  [manifest]
  (let [missing (set/difference required-manifest-keys (set (keys manifest)))
        tiers (:sample/tiers manifest)
        metrics (set (:sample/metrics manifest))]
    (when (seq missing) (fail "manifest is missing required keys" {:missing missing}))
    (when-not (keyword? (:sample/id manifest))
      (fail ":sample/id must be a keyword" {:value (:sample/id manifest)}))
    (when-not (and (string? (:sample/title manifest)) (seq (:sample/title manifest)))
      (fail ":sample/title must be a non-empty string" {:value (:sample/title manifest)}))
    (when-not (dimensions (:sample/dimension manifest))
      (fail "unknown :sample/dimension" {:value (:sample/dimension manifest)
                                          :known dimensions}))
    (when-not (and (keyword? (:sample/genre manifest)) (seq metrics))
      (fail ":sample/genre must be a keyword and :sample/metrics must not be empty" {}))
    (when-let [unknown (seq (set/difference metrics (set (keys metric-units))))]
      (fail "manifest declares unknown metrics" {:unknown (set unknown)}))
    (when-not (= (set quality-tiers) (set (keys tiers)))
      (fail "manifest must declare all quality tiers"
            {:expected (set quality-tiers) :actual (set (keys tiers))}))
    (doseq [[tier {:keys [budget load]}] tiers]
      (when-let [unknown (seq (set/difference (set (keys budget)) metrics))]
        (fail "tier budget uses undeclared metrics" {:tier tier :unknown (set unknown)}))
      (when (and (= tier :meltdown) load)
        (doseq [k [:initial :step :maximum]]
          (when-not (pos? (get load k 0))
            (fail "meltdown load values must be positive" {:key k :load load})))
        (when (> (:initial load) (:maximum load))
          (fail "meltdown initial load exceeds maximum" {:load load}))))
    true))

(defn quality-config
  "Return a validated tier configuration, suitable for handing to a host."
  [manifest tier]
  (valid-manifest? manifest)
  (or (get-in manifest [:sample/tiers tier])
      (fail "unknown quality tier" {:tier tier :known (set quality-tiers)})))

(defn telemetry-frame
  "Create one serializable telemetry sample. Unknown metrics are rejected so
  dashboards do not silently fork their vocabulary."
  [manifest tier frame-n timestamp-ms measurements]
  (valid-manifest? manifest)
  (quality-config manifest tier)
  (let [declared (set (:sample/metrics manifest))
        unknown (set/difference (set (keys measurements)) declared)]
    (when (seq unknown)
      (fail "telemetry contains undeclared metrics" {:unknown unknown}))
    {:telemetry/contract contract-version
     :telemetry/sample (:sample/id manifest)
     :telemetry/tier tier
     :telemetry/frame frame-n
     :telemetry/timestamp-ms timestamp-ms
     :telemetry/measurements measurements}))

(defn next-meltdown-load
  "Advance a meltdown load monotonically, capped at the declared maximum."
  [manifest current]
  (let [{:keys [initial step maximum]}
        (get-in (quality-config manifest :meltdown) [:load])]
    (when-not (and initial step maximum)
      (fail "meltdown tier has no :load ramp" {:sample (:sample/id manifest)}))
    (min maximum (+ (or current (- initial step)) step))))
