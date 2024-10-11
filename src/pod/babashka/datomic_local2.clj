(ns pod.babashka.datomic-local2
  (:refer-clojure :exclude [read read-string])
  (:require
   [bencode.core :as bencode]
   [cognitect.transit :as transit]
   [clojure.java.io :as io]
   [datomic.client.api :as dl])
  (:import
   [java.io PushbackInputStream]
   [java.io EOFException]))

(def debug? true)

(defn debug [& strs]
  (when debug?
    (binding [*out* *err*]
      (apply println strs))))

(defn read-string [^"[B" v]
  (String. v))

;;; bencode

(def stdin (PushbackInputStream. System/in))

(defn read-bencode []
  (try (bencode/read-bencode stdin)
       (catch java.io.EOFException _)))

(defn write-bencode [v]
  (debug :write-bencode v)
  (bencode/write-bencode System/out v)
  (.flush System/out))

;; Transit
(defn read-transit [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json)))

(defn write-transit [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json) v)
    (.toString baos "utf-8")))

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

(defn run
  [& _args]
  (loop []
    (when-let [message (read-bencode)]
      (debug :message message)
      (let [op (some-> (get message "op") read-string keyword)
            id (or (some-> (get message "id") read-string) "unknown")]
        (debug :op op)
        (debug :transit id)
        (case op
          :describe (do
                      (write-bencode {"format" "transit+json"
                                      "namespaces" [{"name" "pod.babashka.datomic-local2"
                                                     "vars" [{"name" "client"}]}]
                                      "id" id
                                      "ops" {"shutdown" {}}})
                      (recur))
          :invoke   (do
                      (try
                        (let [var (-> message
                                      (get "var")
                                      read-string
                                      symbol)
                              _ (debug :invoke :var var)
                              args (-> message
                                       (get "args")
                                       read-string
                                       read-transit)
                              _ (debug :args args)]
                          (if-let [f (lookup var)]
                            (let [result (apply f args)
                                  value (write-transit result)
                                  reply {"value"  value
                                         "id"     id
                                         "status" ["done"]}]
                              (write-bencode reply))
                            (throw (ex-info (str "Var not found: " var) {}))))
                        (catch Throwable e
                          (debug e)
                          (let [reply {"ex-message" (ex-message e)
                                       "ex-data" (write-transit
                                                   (assoc (ex-data e) :type (class e)))
                                       "id" id
                                       "status" ["done" "error"]}]
                            (write-bencode reply))))
                      (recur))
          :shutdown (System/exit 0)
          (do
            (let [reply {"ex-message" "Unknown op"
                         "ex-data"    (pr-str {:op op})
                         "id"         id
                         "status"     ["done" "error"]}]
              (write-bencode reply))
            (recur)))))))


(defn -main [& _args]
  (run))
