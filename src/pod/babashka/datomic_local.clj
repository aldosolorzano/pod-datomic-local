(ns pod.babashka.datomic-local
  (:refer-clojure :exclude [read read-string])
  (:require
   [bencode.core :as bencode]
   [cognitect.transit :as transit]
   [clojure.java.io :as io]
   [datomic.client.api :as d])
  (:import
   [java.io PushbackInputStream]
   [java.io EOFException])
  (:gen-class))

(set! *warn-on-reflection* true)

;; =============================================================================
;; I/O Infrastructure
;; =============================================================================

(def stdin (PushbackInputStream. System/in))
(def stdout System/out)
(def stderr System/err)

(def debug? true)

(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply prn strs))))

(defn write
  "Write a bencoded value `v` to stdout (default) or to the provided output `stream`.

  This is the primary output function for emitting values over the pod protocol.

  Notes:
  - Uses `bencode/write-bencode` for serialization.
  - Calls `flush` to ensure the bytes are pushed to the underlying stream promptly."
  ([v] (write stdout v))
  ([stream v]
   (debug :writing v)
   (bencode/write-bencode stream v)
   (flush)))

(defn write-err
  "Write a bencoded value `v` to stderr (default) or to the provided output `stream`.

  This is typically used for emitting error/debug output without interfering with the
  normal stdout channel used by the pod protocol.

  Notes:
  - Uses `bencode/write-bencode` for serialization.
  - Calls `flush` to ensure the bytes are pushed to the underlying stream promptly."
  ([v] (write stderr v))
  ([stream v]
   (debug :writing v)
   (bencode/write-bencode stream v)
   (flush)))

(defn read-string
  "Convert a Java byte array (`byte[]`, hinted here as `\"[B\"`) into a `String`
  using the platform default charset.

  Prefer using an explicit charset (e.g. UTF-8) when the encoding is known, but this
  function preserves the current behavior."
  [^"[B" v]
  (String. v))

(defn read
  "Read and decode a single bencoded value from `stream` using `bencode/read-bencode`."
  [stream]
  (bencode/read-bencode stream))

(defn read-transit
  "Decode a Transit-JSON encoded string `v` into a Clojure value.

  Implementation details:
  - Encodes `v` as UTF-8 bytes and reads it via a `ByteArrayInputStream`.
  - Uses Transit `:json` format."
  [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json)))

(defn write-transit
  "Encode a Clojure value `v` as a Transit-JSON string (UTF-8).

  Returns the resulting string."
  [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json) v)
    (.toString baos "utf-8")))

;; =============================================================================
;; Datomic client API
;; =============================================================================

(defn serialize-datum [dat] [(.e dat) (.a dat) (.v dat) (.tOp dat)])

(def tansform-tx-data (partial mapv (fn [dat] [(serialize-datum dat)])))

(def client-system
  "Atom that holds the Datomic Client state

   :client - Datomic client instance
   :conn - The connection that will be used as argument for transact and q

   No support for :db-before and :db-after yet."
  (atom {}))

(defn client
  "Create a Datomic Local client and store it in the shared `client-system` atom.

  Returns a map with client:

    {:client <A client object>}

  Notes:
  - This function mutates global state via `swap!` on `client-system`."
  [args]
  (do (swap! client-system assoc :client (d/client args))
      {:client (str (get @client-system :client))}))

(defn connect
  "Connect to a Datomic database using the client stored in `client-system`.

  Looks up the existing client from `client-system` under `:client`, then calls
  `datomic.client.api/connect` (aliased here as `d/connect`) with that client and `args`.
  The resulting connection is stored under `:conn` in `client-system`.

  Returns an empty map `{}`.

  Notes:
  - This function requires `client` to have been called successfully beforehand.
  - This function mutates global state via `swap!` on `client-system`."
  [args]
  (let [client (get @client-system :client)]
    (swap! client-system assoc :conn (d/connect client args)) {}))

(defn create-database
  "Create a Datomic database using the client stored in `client-system`.

  Looks up the existing client from `client-system` under `:client`, then calls
  `datomic.client.api/create-database` (aliased here as `d/create-database`) with that
  client and `args`.

  Returns an empty map `{}`.

  Notes:
  - This function requires `client` to have been called successfully beforehand."
  [args]
  (let [client (get @client-system :client)]
    (d/create-database client args) {}))

(defn delete-database
  "Delete a Datomic database using the value stored in `client-system` under `:conn`.

  Calls `datomic.client.api/delete-database` (aliased here as `d/delete-database`) with
  the stored value and `args`.

  Returns whatever `d/delete-database` returns.

  Notes:
  - This function reads from `client-system` and does not update it."
  [args]
  (let [client (get @client-system :conn)]
    (d/delete-database client args)))

(defn transact
  "Submit a transaction using the connection stored in `client-system`.

  Looks up the connection from `client-system` under `:conn`, then calls
  `datomic.client.api/transact` (aliased here as `d/transact`) with that connection and `args`.

  Post-processes the returned transaction report by:
  - Converting `:db-after` and `:db-before` values to strings (via `str`)
  - Transforming `:tx-data` with `tansform-tx-data`

  Returns the transformed transaction report map.

  Notes:
  - This function requires `connect` to have been called successfully beforehand."
  [args]
  (let [client (get @client-system :conn)
        tx (d/transact client args)]
    (-> tx
        (update :db-after str)
        (update :db-before str)
        (update :tx-data tansform-tx-data))))

(defn q
  "Runs a Datomic query against the current local connection.

  This is a small convenience wrapper around `datomic.client.api/q` that
  automatically supplies the database value from your active connection in
  `client-system`.

  **Behavior**
  - Reads the connection from `@client-system` at key `:conn`
  - Calls `d/db` on that connection to obtain a database value
  - Executes `d/q` with the provided `query` against that database

  **Parameters**
  - `query`: A Datomic query form (map or list style). If your query requires
    inputs, those inputs must already be included in the `query` value (this
    wrapper only accepts a single argument).

  **Returns**
  - The query result as returned by `d/q` (typically a set of tuples).

  **Notes**
  - If `client-system` is not initialized, does not contain `:conn`, or the
    connection is invalid, this function will throw when `d/db`/`d/q` is called."
  [query]
  (d/q query (d/db (get @client-system :conn))))

(defn q-history
  "A read-only, historical view of the current Datomic database.

  This is defined by calling `d/q-history` on the database value returned from calling `d/db` to the
  active connection stored under `:conn` in `client-system`.

  It will expose all historical assertions/retractions (depending on how you query),
  rather than only the latest state."
  [query]
  (d/q query (d/history (d/db (get @client-system :conn)))))

(def lookup
  {'pod.babashka.datomic-local/client client
   'pod.babashka.datomic-local/connect connect
   'pod.babashka.datomic-local/create-database create-database
   'pod.babashka.datomic-local/delete-database delete-database
   'pod.babashka.datomic-local/transact transact
   'pod.babashka.datomic-local/q q
   'pod.babashka.datomic-local/q-history q-history})

;; =============================================================================
;; Pod Runner
;; =============================================================================

(defn main
  [& _args]
  (pr-str "starting")
  (loop []
    (let [message (try
                    (read stdin)
                    (catch EOFException _ ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (-> message
                     (get "op")
                     read-string
                     keyword)
              id (some-> (get message "id")
                         read-string)
              id (or id "unknown")]
          (case op
            :describe (do
                        (write {"format" "transit+json"
                                "namespaces" [{"name" "pod.babashka.datomic-local"
                                               "vars" [{"name" "create-database"}
                                                       {"name" "client"}
                                                       {"name" "connect"}
                                                       {"name" "q"}
                                                       {"name" "transact"}
                                                       {"name" "delete-database"}
                                                       {"name" "q-history"}]}]
                                "id" id
                                "ops" {"shutdown" {}}})
                        (recur))
            :invoke   (do
                        (try
                          (let [var (-> message
                                        (get "var")
                                        (read-string)
                                        symbol)
                                args (-> message
                                         (get "args")
                                         (read-string)
                                         (read-transit))]
                            (if-let [f (lookup var)]
                              (let [result (apply f args)
                                    value (write-transit result)
                                    reply {"value"  value
                                           "id"     id
                                           "status" ["done"]}]
                                (write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (debug e)
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data" (write-transit
                                                    (assoc (ex-data e)
                                                           :type (str (class e))))
                                         "id" id
                                         "status" ["done" "error"]}]
                              (write stdout reply))))
                        (recur))
            :shutdown (System/exit 0)
            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data"    (pr-str {:op op})
                           "id"         id
                           "status"     ["done" "error"]}]
                (write reply))
              (recur))))))))

(defn -main [& _args]
  (main))
