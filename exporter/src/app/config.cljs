;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["node:buffer" :as buffer]
   ["node:crypto" :as crypto]
   ["node:fs" :as node-fs]
   ["node:path" :as node-path]
   ["node:process" :as process]
   [app.common.data :as d]
   [app.common.flags :as flags]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.version :as v]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(l/set-level! :info)

(def ^:private defaults
  {:public-uri "http://localhost:3449"
   ;; :internal-uri nil ;; internal-uri cannot be nil
   :tenant "default"
   :host "localhost"
   :http-server-port 6061
   :http-server-host "0.0.0.0"
   :tempdir "/tmp/penpot"
   :redis-uri "redis://redis/0"})

(def ^:private schema:config
  [:map {:title "config"}
   [:secret-key :string]
   [:secret-key-file {:optional true} :string]
   [:public-uri {:optional true} ::sm/uri]
   [:public-uri-file {:optional true} :string]
   [:internal-uri {:optional true} ::sm/uri]
   [:exporter-shared-key {:optional true} :string]
   [:host {:optional true} :string]
   [:tenant {:optional true} :string]
   [:flags {:optional true} [::sm/set :keyword]]
   [:redis-uri {:optional true} :string]
   [:tempdir {:optional true} :string]
   [:browser-pool-max {:optional true} ::sm/int]
   [:browser-pool-min {:optional true} ::sm/int]])

(def ^:private decode-config
  (sm/decoder schema:config sm/string-transformer))

(def ^:private explain-config
  (sm/explainer schema:config))

(def ^:private valid-config?
  (sm/validator schema:config))

(defn- parse-flags
  [config]
  (flags/parse (:flags config)))

(defn- read-env
  [prefix]
  (let [env    (unchecked-get process "env")
        kwd    (fn [s] (-> (str/kebab s) (str/keyword)))
        prefix (str prefix "_")
        len    (count prefix)]
    (reduce (fn [res key]
              (let [val (unchecked-get env key)
                    key (str/lower key)]
                (cond-> res
                  (str/starts-with? key prefix)
                  (assoc (kwd (subs key len)) val))))
            {}
            (js/Object.keys env))))

(defn- read-config-file
  [value hint max-size]
  (when-not (.isAbsolute node-path value)
    (throw (js/Error. hint)))
  (let [stat (.lstatSync node-fs value)]
    (when (or (not (.isFile stat))
              (.isSymbolicLink stat)
              (> (.-size stat) max-size))
      (throw (js/Error. hint))))
  (let [value (-> (.readFileSync node-fs value "utf8")
                  (str/trim))]
    (when (str/blank? value)
      (throw (js/Error. hint)))
    value))

(defn- resolve-file-backed-config
  [defaults env]
  (when (and (:secret-key env) (:secret-key-file env))
    (throw (js/Error.
            "PENPOT_SECRET_KEY and PENPOT_SECRET_KEY_FILE cannot both be set")))
  (when (and (:public-uri env) (:public-uri-file env))
    (throw (js/Error.
            "PENPOT_PUBLIC_URI and PENPOT_PUBLIC_URI_FILE cannot both be set")))
  (cond-> (merge defaults env)
    (:secret-key-file env)
    (assoc :secret-key
           (read-config-file
            (:secret-key-file env)
            "PENPOT_SECRET_KEY_FILE must name a small, absolute, regular, non-symlink file"
            1024))
    (:secret-key-file env)
    (dissoc :secret-key-file)
    (:public-uri-file env)
    (assoc :public-uri
           (read-config-file
            (:public-uri-file env)
            "PENPOT_PUBLIC_URI_FILE must name a small, absolute, regular, non-symlink file"
            4096))
    (:public-uri-file env)
    (dissoc :public-uri-file)))

(defn- prepare-config
  []
  (let [env  (read-env "penpot")
        env  (d/without-nils env)
        data (resolve-file-backed-config defaults env)
        data (decode-config data)]

    (when-not (valid-config? data)
      (let [explain (explain-config data)]
        (println (sm/humanize-explain explain))
        (process/exit -1)))

    data))

(def config
  (prepare-config))

(def version
  (v/parse "%version%"))

(def flags
  (parse-flags config))

(defn get
  "A configuration getter."
  ([key]
   (c/get config key))
  ([key default]
   (c/get config key default)))

(defn get-internal-uri
  "Returns internal-uri if set, otherwise falls back to public-uri."
  []
  (or (c/get config :internal-uri)
      (c/get config :public-uri)))

(def management-key
  (let [key (or (c/get config :exporter-shared-key)
                (let [secret-key  (c/get config :secret-key)
                      derived-key (crypto/hkdfSync "blake2b512" secret-key, "exporter" "" 32)]
                  (-> (.from buffer/Buffer derived-key)
                      (.toString "base64url"))))]
    (l/inf :hint "exporter key initialized" :key (d/obfuscate-string key))
    key))
