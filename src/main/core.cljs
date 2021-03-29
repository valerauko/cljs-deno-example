(ns core
  (:require [server :refer [http-server]]))

(defn handler
  [_request]
  {:status 200
   :body "Hello, world"})

(defn init []
  (http-server handler))
