(ns pod.babashka.datomic-local-test
  (:require [babashka.pods :as pods]
            [datomic.client.api :as d]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Set up and load pod
;; =============================================================================

(def pod-id (pods/load-pod ["clojure" "-M" "-m" "pod.babashka.datomic-local"]))
(require '[pod.babashka.datomic-local :as dl])

(def client (dl/client {:server-type :datomic-local
                        :storage-dir :mem
                        :system "pod"}))

#_(dl/delete-database {:db-name "movies"})
(dl/create-database {:db-name "movies"})

(def conn (dl/connect  {:db-name "movies"}))

(deftest client-creation
  (is (= [:client] (keys client))))

(def movie-schema [{:db/ident :movie/sku
                    :db/valueType :db.type/string
                    :db/unique :db/unique
                    :db/cardinality :db.cardinality/one
                    :db/doc "The unique id of the movie"}

                   {:db/ident :movie/title
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one
                    :db/doc "The title of the movie"}

                   {:db/ident :movie/genre
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one
                    :db/doc "The genre of the movie"}

                   {:db/ident :movie/release-year
                    :db/valueType :db.type/long
                    :db/cardinality :db.cardinality/one
                    :db/doc "The year the movie was released in theaters"}])
(def first-movies [{:movie/sku "SKU-1"
                    :movie/title "The Goonies"
                    :movie/genre "action/adventure"
                    :movie/release-year 1985}
                   {:movie/sku "SKU-2"
                    :movie/title "Command"
                    :movie/genre "thriller/action"
                    :movie/release-year 1985}
                   {:movie/sku "SKU-3"
                    :movie/title "Repo Man"
                    :movie/genre "punk dystopia"
                    :movie/release-year 1984}])

(def movies-query '[:find ?movie-title
                    :where [_ :movie/title ?movie-title]])

(dl/transact {:tx-data movie-schema})

(deftest transact-and-q-test
  (testing "d/transact execution and d/q retrieval"
    (testing ":db/add with tx-map collection"
      (let [_tx (dl/transact {:tx-data first-movies})]
        (is (= [["Command"] ["The Goonies"] ["Repo Man"]]
               (dl/q movies-query)))))
    (testing ":db/add with tx-map collection"
      (let [_tx-retract (dl/transact {:tx-data [[:db/add [:movie/sku "SKU-2"] :movie/title "Commando"]
                                                [:db/add "datomic.tx" :db/doc "fix typo"]]})]
        (is (= [["Commando"] ["The Goonies"] ["Repo Man"]]
               (dl/q movies-query)))))
    (testing "query history of movie titles"
      (is (= [[13194139533319 "SKU-3" "Repo Man" true]
              [13194139533319 "SKU-2" "Command" true]
              [13194139533319 "SKU-1" "The Goonies" true]
              [13194139533320 "SKU-2" "Commando" true]
              [13194139533320 "SKU-2" "Command" false]]
             (->> (dl/q-history
                   '[:find ?tx ?sku ?val ?op
                     :where
                     [?movie :movie/title ?val ?tx ?op]
                     [?movie :movie/sku ?sku]])
                  (sort-by first)))))))

(comment
  (def all-titles-q '[:find ?movie-title
                      :where [_ :movie/title ?movie-title]])
  (d/transact conn {:tx-data movie-schema})
  (dl/transact {:tx-data first-movies})
  (d/history (d/db conn)))
