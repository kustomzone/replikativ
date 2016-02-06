(ns replikativ.core
  "Replication related pub-sub protocols."
  (:require [replikativ.crdt.materialize :refer [key->crdt]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.protocols :refer [-downstream]]
            [kabel.peer :refer [drain]]
            [konserve.core :as k]
            [replikativ.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            [clojure.data :refer [diff]]
            #?(:clj [full.async :refer [<? <<? go-for go-try go-try> go-loop-try go-loop-try> alt?]])
            [kabel.platform :refer [client-connect!] :include-macros true]
            #?(:clj [clojure.core.async :as async
                     :refer [>! timeout chan put! pub sub unsub close!]]
                    :cljs [cljs.core.async :as async
                           :refer [>! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)]
                            [full.cljs.async :refer [<<? <? go-for go-try go-try> go-loop-try go-loop-try> alt?]])))


(defn- get-error-ch [peer]
  (get-in @peer [:volatile :error-ch]))


(defn filter-subs
  "Filters downstream publications depending on subscriptions sbs."
  [store sbs downstream]
  (go-try (->> (go-for [[user crdts] downstream
                        [crdt-id pub] crdts
                        :when (get-in sbs [user crdt-id])]
                       [[user crdt-id] pub])
               <<?
               (filter #(-> % second :op))
               (reduce #(assoc-in %1 (first %2) (second %2)) {}))))


(defn- publication-loop
  "Reply to publications by sending an update value filtered to subscription."
  [store error-ch pub-ch out identities pn remote-pn]
  (go-try> error-ch
           (let [downstream-list (->> (go-for [[user crdts] identities
                                               id crdts]
                                              [[user id]
                                               (let [{:keys [crdt state]} (<? (k/get-in store [[user id]]))]
                                                 {:crdt crdt
                                                  :method :new-state
                                                  :op (into {} state)})])
                                      <<?
                                      (filter (comp not empty? :op second)))
                 downstream (reduce #(assoc-in %1 (first %2) (second %2)) nil downstream-list)]
             (when downstream
               (debug "initial state publication:" downstream)
               (>! out {:type :pub/downstream
                        :downstream (<? (filter-subs store identities downstream))
                        :peers pn
                        :id (*id-fn*)})))

           (go-loop-try> error-ch
                         [{:keys [downstream id] :as p} (<? pub-ch)]
                         (when-not p
                           (info pn "publication-loop ended for " identities))
                         (when p
                           (let [new-downstream (<? (filter-subs store identities downstream))]
                             (info pn "publication-loop: new downstream " downstream
                                   "\nsubs " identities new-downstream "\nto " remote-pn)
                             (when-not (empty? new-downstream)
                               (info pn "publication-loop: sending " new-downstream "to" remote-pn)
                               (>! out (assoc p
                                              :downstream new-downstream
                                              :peer pn
                                              :id id)))
                             (recur (<? pub-ch)))))))


(defn subscribe
  "Adjust publication stream and propagate subscription requests."
  [peer store sub-ch out]
  (let [{:keys [chans log]} (-> @peer :volatile)
        [bus-in bus-out] chans
        pn (:name @peer)]
    (sub bus-out :sub/identities out)
    (go-loop-try> (get-error-ch peer)
                  [{identities :identities id :id :as s} (<? sub-ch)
                   init true
                   old-pub-ch nil]
                  (if s
                    (let [old-subs (:subscriptions @peer)
                          ;; TODO make subscription configurable
                          new-subs (:subscriptions (swap! peer
                                                          update-in
                                                          [:subscriptions]
                                                          (partial merge-with set/union) identities))
                          remote-pn (:peer s)
                          pub-ch (chan)
                          [_ _ common-subs] (diff new-subs identities)]
                      (info pn "subscribe: starting subscription " id " from " remote-pn)
                      (debug pn "subscribe: subscriptions " identities)
                      ;; properly close previous publication-loop
                      (when old-pub-ch
                        (unsub bus-out :pub/downstream old-pub-ch)
                        (close! old-pub-ch))
                      ;; and restart
                      (sub bus-out :pub/downstream pub-ch)
                      (publication-loop store (get-error-ch peer) pub-ch out identities pn remote-pn)

                      (when (and init (= new-subs old-subs)) ;; subscribe back at least exactly once on init
                        (>! out {:type :sub/identities :identities new-subs :peer pn :id id}))
                      (when (not (= new-subs old-subs))
                        (let [msg {:type :sub/identities :identities new-subs :peer pn :id id}]
                          (alt? [[bus-in msg]]
                                :wrote

                                (timeout 5000)
                                ;; TODO disconnect peer
                                (throw (ex-info "bus-in was blocked. Subscription broken."
                                                {:type :bus-in-block
                                                 :failed-put msg
                                                 :was-blocked-by (<? bus-in)})))))

                      ;; propagate (internally) that the remote has subscribed (for connect)
                      ;; also guarantees sub/identities is sent to remote peer before sub/identities-ack!
                      (let [msg {:type :sub/identities-ack :identities common-subs :peer remote-pn :id id}]
                        (alt? [[bus-in msg]]
                              :wrote

                              (timeout 5000)
                              ;; TODO disconnect peer
                              (throw (ex-info "bus-in was blocked. Subscription broken."
                                              {:type :bus-in-block
                                               :failed-put msg
                                               :was-blocked-by (<? bus-in)}))))
                      (>! out {:type :sub/identities-ack :identities common-subs :peer remote-pn :id id})
                      (info pn "subscribe: finishing " id)

                      (recur (<? sub-ch) false pub-ch))
                    (do (info "subscribe: closing old-pub-ch")
                        (unsub bus-out :pub/downstream old-pub-ch)
                        (unsub bus-out :sub/identities out)
                        (close! old-pub-ch))))))


(defn commit-pubs [store pubs]
  (go-try (->> (go-for [[user crdts] pubs
                        [crdt-id pub] crdts]
                       [[user crdt-id]
                        (<? (k/update-in store [[user crdt-id]]
                                         (fn [{:keys [description public state crdt]}]
                                           (let [state (or state (key->crdt (:crdt pub)))]
                                             {:crdt (or crdt (:crdt pub))
                                              :description (or description
                                                               (:description pub))
                                              :public (or (:public pub) public false)
                                              :state (-downstream state (:op pub))}))))])
               <<?)))


(defn publish
  "Synchronize downstream publications."
  [peer store pub-ch bus-in out]
  (go-loop-try> (get-error-ch peer)
                [{:keys [downstream id] :as p} (<? pub-ch)]
                (when p
                  (let [pn (:name @peer)
                        remote (:peer p)]
                    (info pn "publish: " p)
                    ;; update all crdts of all users
                    (let [up-downstream (<? (commit-pubs store downstream))]
                      (>! out {:type :pub/downstream-ack
                               :peer (:peer p)
                               :id id})
                      (when (some true? (map #(let [[old-state up-state] (second %)]
                                                (not= old-state up-state)) up-downstream))
                        (info pn "publish: downstream ops " downstream)
                        (let [msg (assoc p :peer pn)]
                          (alt? [[bus-in msg]]
                                (debug pn "publish: sent new downstream ops")

                                (timeout 5000) ;; TODO make tunable
                                (throw (ex-info "bus-in was blocked. Subscription broken."
                                                {:type :bus-in-block
                                                 :failed-put msg
                                                 :was-blocked-by (<? bus-in)})))))))
                  (recur (<? pub-ch)))))


(defn wire
  "Wire a peer to an output (response) channel and a publication by :type of the input."
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:sub/identities :sub/identities
                                    :pub/downstream :pub/downstream} type)
                                  :unrelated)))
                  {:keys [store chans log]} (:volatile @peer)
                  name (:name @peer)
                  [bus-in bus-out] chans
                  pub-ch (chan)
                  sub-ch (chan)]

              (sub p :sub/identities sub-ch)
              (subscribe peer store sub-ch out)

              (sub p :pub/downstream pub-ch)
              (publish peer store pub-ch bus-in out)

              (sub p :unrelated new-in true)))
    [peer [new-in out]]))


(defn handle-connection-request
  "Service connection requests."
  [peer conn-ch out]
  (go-loop-try> (get-error-ch peer)
                [{:keys [url id reconnect?] :as c} (<? conn-ch)]
                ;; keep connection scope for reconnects
                (when c
                  ((fn connection []
                     (go-try
                      (try
                        (info (:name @peer) "connecting to:" url)
                        (let [{{:keys [log middleware]
                                {:keys [read-handlers write-handlers]} :store
                                [bus-in bus-out] :chans} :volatile
                               pn :name
                               subs :subscriptions} @peer
                              conn-err-ch (chan)
                              _ (async/take! conn-err-ch (fn [e]
                                                           (go-try
                                                            (error "connection failed:" e)
                                                            (<? (timeout (* 60 1000)))
                                                            (when reconnect?
                                                              (debug "retrying to connect")
                                                              (connection)))))
                              [c-in c-out] (<? (client-connect! url
                                                                conn-err-ch
                                                                read-handlers
                                                                write-handlers))
                              subed-ch (chan)
                              sub-id (*id-fn*)]
                          ;; handshake
                          (sub bus-out :sub/identities-ack subed-ch)
                          ((comp drain wire middleware) [peer [c-in c-out]])
                          (>! c-out {:type :sub/identities :identities subs :peer pn :id sub-id})
                          ;; HACK? wait for ack on backsubscription, is there a simpler way?
                          (<? (go-loop-try [{id :id :as c} (<? subed-ch)]
                                           (debug "connect: backsubscription?" sub-id c)
                                           (when (and c (not= id sub-id))
                                             (recur (<? subed-ch)))))
                          (async/close! subed-ch)

                          (>! out {:type :connect/peer-ack
                                   :url url
                                   :id id
                                   :peer (:peer c)}))
                        (catch #?(:clj Throwable :cljs js/Error) e
                          (>! out {:type :connect/peer-ack
                                   :url url
                                   :id id
                                   :error e}))))))
                  (recur (<? conn-ch)))))

(defn connect
  [[peer [in out]]]
  (let [new-in (chan)]
    (go-try (let [p (pub in (fn [{:keys [type]}]
                              (or ({:connect/peer :connect/peer} type)
                                  :unrelated)))
                  {:keys [chans]} (:volatile @peer)
                  [bus-in bus-out] chans
                  conn-ch (chan)]

              (sub p :connect/peer conn-ch)
              (handle-connection-request peer conn-ch out)

              (sub p :unrelated new-in true)))
    [peer [new-in out]]))
