(ns pod.babashka.datomic-local
  (:refer-clojure :exclude [read read-string])
  (:require
   [bencode.core :as bencode]
   [cognitect.transit :as transit]
   [clojure.java.io :as io]
   [datomic.client.api :as dl])
  (:import
   [java.io PushbackInputStream]
   [java.io EOFException])
  (:gen-class))

(def stdin (PushbackInputStream. System/in))

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read-transit [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json)))

(defn write-transit [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json) v)
    (.toString baos "utf-8")))

(defn read []
  (bencode/read-bencode stdin))

(def debug? false)

(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply println strs))))

(def client dl/client)
(def connect dl/connect)
(def create-database dl/create-database)
(def delete-database dl/delete-database)
(def transact dl/transact)
(def q dl/q)
(def db dl/db)

(def lookup
  {'pod.babashka.datomic-local/client client
   'pod.babashka.datomic-local/connect connect
   'pod.babashka.datomic-local/create-database create-database
   'pod.babashka.datomic-local/delete-database delete-database
   'pod.babashka.datomic-local/transact transact
   'pod.babashka.datomic-local/q q
   'pod.babashka.datomic-local/db db})

(defn main
  [& _args]
  (pr-str "starting")
  (loop []
    (let [message (try
                    (read)
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
                                                       {"name" "db"}
                                                       {"name" "transact"}
                                                       {"name" "delete-database"}]}]
                                "id" id
                                "ops" {"shutdown" {}}})
                        (recur))
            :invoke   (do
                        (try
                          (let [var (-> message
                                        (get "var")
                                        read-string
                                        symbol)
                                args (-> message
                                         (get "args")
                                         read-string
                                         read-transit)]
                            (if-let [f (lookup var)]
                              (let [result (apply f args)
                                    value (write-transit result)
                                    reply {"value"  value
                                           "id"     id
                                           "status" ["done"]}]
                                (write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (binding [*out* *err*]
                              (println e))
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data" (write-transit
                                                     (assoc (ex-data e) :type (class e)))
                                         "id" id
                                         "status" ["done" "error"]}]
                              (write reply))))
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
