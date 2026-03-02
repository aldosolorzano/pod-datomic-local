# pod-datomic-local

Below are some practical examples based directly on the usage patterns covered in `test/pod/babashka/datomic_local_test.clj`.

### Load the pod and require the wrapper namespace

```clojure
(require '[babashka.pods :as pods])

(def pod-id
  (pods/load-pod ["clojure" "-M" "-m" "pod.babashka.datomic-local"]))

(require '[pod.babashka.datomic-local :as dl])
```

### Create a Datomic Local client (in-memory)

```clojure
(def client
  (dl/client {:server-type :datomic-local
              :storage-dir :mem
              :system "pod"}))
```

### Create a database and connect

```clojure
;; optional cleanup
#_(dl/delete-database {:db-name "movies"})

(dl/create-database {:db-name "movies"})

(def conn
  (dl/connect {:db-name "movies"}))
```

### Define a schema and transact it

```clojure
(def movie-schema
  [{:db/ident :movie/sku
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

(dl/transact {:tx-data movie-schema})
```

### Transact some data

```clojure
(def first-movies
  [{:movie/sku "SKU-1"
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

(dl/transact {:tx-data first-movies})
```

### Query with `dl/q`

```clojure
(def movies-query
  '[:find ?movie-title
    :where [_ :movie/title ?movie-title]])

(dl/q movies-query)
;; => [["Command"] ["The Goonies"] ["Repo Man"]]
```

### Update data using `:db/add` datoms

This shows updating an entity by lookup ref (`[:movie/sku "SKU-2"]`) and also adding metadata to the transaction entity (`"datomic.tx"`).

```clojure
(dl/transact
  {:tx-data [[:db/add [:movie/sku "SKU-2"] :movie/title "Commando"]
             [:db/add "datomic.tx" :db/doc "fix typo"]]})

(dl/q movies-query)
;; => [["Commando"] ["The Goonies"] ["Repo Man"]]
```

### Query history with `dl/q-history`

```clojure
(->> (dl/q-history
       '[:find ?tx ?sku ?val ?op
         :where
         [?movie :movie/title ?val ?tx ?op]
         [?movie :movie/sku ?sku]])
     (sort-by first))
;; => [[13194139533319 "SKU-3" "Repo Man" true]
;;     [13194139533319 "SKU-2" "Command" true]
;;     [13194139533319 "SKU-1" "The Goonies" true]
;;     [13194139533320 "SKU-2" "Commando" true]
;;     [13194139533320 "SKU-2" "Command" false]]
```

### Time travel queries: `dl/q-as-of` and `dl/q-since`

`dl/q-as-of` queries the database *as it existed at a specific t*:

```clojure
(dl/q-as-of
  '[:find ?title
    :where
    [?movie :movie/title ?title]
    [?movie :movie/sku "SKU-2"]]
  13194139533319)
;; => [["Command"]]
```

`dl/q-since` queries datoms for transactions that occurred *after a specific t*:

```clojure
(dl/q-since movies-query 13194139533319)
;; => [["Commando"]]
```

### List databases

```clojure
(dl/list-databases {})
;; => ["movies"]
```

### Delete a database (optional)

```clojure
(dl/delete-database {:db-name "movies"})
```
