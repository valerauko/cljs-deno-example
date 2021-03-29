(ns server
  (:require ["https://deno.land/std@0.91.0/http/server.ts" :as http]
            [cljs.core.async :refer [go go-loop chan >! <! close!]]))

;; the AsyncIterable -> core.async/chan implementation is based on
;; @jjsullivan5196's gist
;; https://gist.github.com/jjsullivan5196/0904057a2ec3eb080de5c4d6f45da630

(defn onto-chan
  [channel iterator]
  (.. iterator
      (next)
      (then
       (fn [item]
         (if (.-done item)
           (close! channel)
           (go (when (>! channel [(.-value item) nil])
                 (onto-chan channel iterator)))))
       (fn [error]
         (go (when (>! channel [nil error])
               (onto-chan channel iterator)))))))

(defn to-chan
  [iterable]
  (let [channel (chan)
        iterator (js-invoke iterable js/Symbol.asyncIterator)]
    (go (onto-chan channel iterator))
    channel))

(defn server-chan
  [opts]
  (-> opts
      clj->js
      http/serve
      to-chan))

(defn headers->map
  [headers]
  (reduce
   (fn [aggr [k v]] (assoc aggr (keyword k) v))
   {}
   (.entries headers)))

(defn req->ring
  [^http/ServerRequest request]
  (let [conn ^js/Deno.Conn (.-conn request)]
    {:body (.-body request)
     :headers (headers->map (.-headers request))
     :request-method (keyword (.toLowerCase (.-method request)))
     :uri (.-url request) ;; just a string
     :protocol (.-proto request)
     :remote-addr (->> conn (.-remoteAddr) (.-hostname))
     :scheme :http ;; no idea how to retrieve it
     ;; can't retrieve the actual hostname from the request,
     ;; it just gives 127.0.0.1
     :server-name (->> conn (.-localAddr) (.-hostname))
     :server-port (->> conn (.-localAddr) (.-port))}))

(defn ^http/Response ring->response
  [ring-map]
  (clj->js ring-map))

(defn http-server
  ([handler] (http-server handler {}))
  ([handler {:keys [host port]
             :or {host "0.0.0.0" port 8080}}]
   (println (str "Starting Deno HTTP server at " host ":" port))
   (let [server (server-chan {:hostname host :port port})]
     (go-loop []
       (when-let [[req err] (<! server)]
         (when req (->> req
                        (req->ring)
                        (handler)
                        (ring->response)
                        (.respond req)))
         (when err (println "Error:" err))
         (recur))))))
