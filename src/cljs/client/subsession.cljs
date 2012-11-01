(ns session.client.subsession
  (:require
   [session.client.loop-creator :as loop-creator]
   [session.client.loop :as loop]
   [session.client.mvc :as mvc]
   [session.client.session :as session]

   [cljs.reader :as reader])


  (:use-macros [cljs-jquery.macros :only [$]])
  (:require-macros [fetch.macros :as pm])
  )



(defprotocol ISubsession
  (insert-new-loop [this session-view event])
  (delete-loop [this session-view event])
  (evaluate-loop [this session-view event]))

(def callbacks (atom {}))

(def ws  (new js/WebSocket "ws://localhost:8090/service"))

(aset ws "onmessage"
      (fn [e] (let [data (cljs.reader/read-string (.-data e))]

           ((@callbacks (:id data))
           (:data data)
           ))))

(defn response-handler [event-model]
      #(reset! (:output event-model) %))

(defn evaluate-clj [event-model]
  (swap! callbacks assoc (:id event-model) (response-handler event-model))
  (.send
    ws
   (pr-str {
           :op :evaluate-clj
           :data @(:input event-model)
           :id (:id event-model)})))

(defn evaluate-cljs [event-model]
  (pm/remote
   (compile-expr-string @(:input event-model) (:id event-model))
   [result]
   ;;(js/alert (pr-str (:result result)))
   (reset! (:output event-model) (let [x (js/eval (:result result))] (if x x nil)))))


(deftype Subsession [model]

   ILookup
  (-lookup [o k] (model k))
  (-lookup [o k not-found] (model k not-found))

  IPrintable
  (-pr-seq [a opts]
    (concat  ["#session/subsession "] (-pr-seq (assoc model :loops @(:loops model)) opts) ""))




  mvc/IMVC
  (view [this]

    ($ [:div.subsession

        (mvc/render (loop-creator/LoopCreator. true))

        (map mvc/render @(:loops model))
        ] (data "model" model)))
  (control [this session-view]
    ($ session-view
       (on "insert-new-loop" #(insert-new-loop this session-view %)))
    ($ session-view (on "delete-loop" #(delete-loop this session-view %)))
    ($ session-view (on "evaluate-loop" #(evaluate-loop this session-view %))))

  ISubsession
  (insert-new-loop [this session-view event]
    (let [
        event-target (. event -target)
        event-model ($ event-target (data "model"))
        loop-model (let [id (session/new-loop-id)] (loop/Loop. {:id id :input (atom "") :output (atom nil)}))
        loop-view (mvc/render loop-model)
        session-model this
          ]

    ;;(def insert-test [event event-target event-model loop-model loop-view])
    (if
        (= event-model "loop-creator")
      (swap! (:loops session-model) #(vec (concat [loop-model] %)))
      (swap! (:loops session-model)
             #(let
                  [[left right] (split-with (fn [m] (not= m event-model)) %)]
                (vec (concat left [event-model loop-model] (rest right))))))
    ($ loop-view (insertAfter event-target))
    ;;(js/alert (str ($ event-target (data "model"))))
    ($ loop-view (trigger "post-render"))))

  (delete-loop [this session-view event]
    (let [
        event-target (. event -target)
        event-model ($ event-target (data "model"))
        session-model this
        ]
     ($ event-target (remove))
     (swap! (:loops session-model) #(vec (filter (fn [m] (not= m event-model)) %)))))

  (evaluate-loop [this session-view event]

    (let [
        event-target (. event -target)
        event-model ($ event-target (data "model"))
          ]

      (cond
       (= :cljs (:type this)) (evaluate-cljs event-model)
       (= :clj (:type this)) (evaluate-clj event-model)
       )
        )))

(reader/register-tag-parser! "subsession" (fn [x] (Subsession. (assoc x :loops (atom (:loops x)))) ))
