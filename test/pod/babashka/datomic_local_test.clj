(ns pod.babashka.datomic-local-test
  (:require [babashka.pods :as pods]
            [clojure.test :refer [deftest is testing]]))

(def pod-id (pods/load-pod ["clojure" "-M" "-m" "pod.babashka.datomic-local"]))
;; (pods/load-pod "/home/aldo.solorzano/dev/experiments/pod-datomic-local/pod-babashka-datomic-local")
(require '[pod.babashka.datomic-local :as dl])

(def client (dl/client {:server-type :datomic-local
                        :storage-dir :mem
                        :system "pod"}))

;; (deftest client-creation
;;   (is (true? (dl/create-database client {:db-name "movies"}))))



;; (def conn (dl/connect client {:db-name "movies"}))

;; (def movie-schema [{:db/ident :movie/title
;;                     :db/valueType :db.type/string
;;                     :db/cardinality :db.cardinality/one
;;                     :db/doc "The title of the movie"}

;;                    {:db/ident :movie/genre
;;                     :db/valueType :db.type/string
;;                     :db/cardinality :db.cardinality/one
;;                     :db/doc "The genre of the movie"}

;;                    {:db/ident :movie/release-year
;;                     :db/valueType :db.type/long
;;                     :db/cardinality :db.cardinality/one
;;                     :db/doc "The year the movie was released in theaters"}])
;; (def first-movies [{:movie/title "The Goonies"
;;                     :movie/genre "action/adventure"
;;                     :movie/release-year 1985}
;;                    {:movie/title "Commando"
;;                     :movie/genre "thriller/action"
;;                     :movie/release-year 1985}
;;                    {:movie/title "Repo Man"
;;                     :movie/genre "punk dystopia"
;;                     :movie/release-year 1984}])
;; (def db (dl/db conn))
;; (def all-titles-q '[:find ?movie-title
;;                     :where [_ :movie/title ?movie-title]])
