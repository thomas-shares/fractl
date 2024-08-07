(ns fractl.inference.embeddings.pgvector
  (:require [fractl.util :as u]
            [fractl.inference.embeddings.protocol :as p]
            [fractl.inference.embeddings.internal.registry :as r]
            [fractl.inference.embeddings.internal.pgvector :as pgv]))

;;;; sample config.edn entry:
;; {:publish-schema {:vectordb :pgvector
;;                   :config {:host #$ [PGVECTOR_DB_HOST "localhost"]
;;                            :port #$ [PGVECTOR_DB_PORT 5432]
;;                            :dbname #$ [PGVECTOR_DB_NAME "postgres"]
;;                            :user #$ [PGVECTOR_DB_USERNAME "postgres"]
;;                            :password #$ [PGVECTOR_DB_PASSWORD "postgres"]}}}

(defn make []
  (let [db-conn (u/make-cell)]
    (reify p/EmbeddingDb
      (open-connection [this config]
        (u/safe-set-once db-conn #(pgv/open-connection config))
        this)
      (close-connection [_]
        (when (pgv/close-connection @db-conn)
          (u/safe-set db-conn nil)
          true))
      (embed-tool [_ spec]
        (pgv/embed-planner-tool @db-conn spec))
      (update-tool [_ spec]
        (pgv/update-planner-tool @db-conn spec))
      (delete-tool [_ spec]
        (pgv/delete-planner-tool @db-conn spec))
      (embed-document-chunk [_ app-uuid text-chunk]
        (pgv/add-document-chunk @db-conn app-uuid text-chunk))
      (get-document-classname [_ app-uuid]
        (pgv/get-document-classname app-uuid))
      (get-planner-classname [_ app-uuid]
        (pgv/get-planner-classname app-uuid))
      (find-similar-objects [_ query-spec limit]
        (pgv/find-similar-objects @db-conn query-spec limit)))))

(def make-db
  (memoize
   (fn [config]
     (let [db (make)]
      (when (p/open-connection db config)
        db)))))

(r/register-db :pgvector make-db)
