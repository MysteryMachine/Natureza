(ns rts.controller
  (:use arcadia.core
        folha.core
        [rts.entities.unity :exclude [start! update! ->selected]]
        rts.entities.core
        rts.squad)
  (:require [rts.entities.unity :as e])
  (:import [UnityEngine Ray
            Physics RaycastHit]))

(load-hooks)
(declare start!)

(defn ->selected [this] (-> this ->state :selected-ids))
(defn ->target   [this] (-> this ->state :target))
(defn ->entity-states [this] (-> this ->state :entity-ids))
(defn ->entities [this] (map ->obj (->entity-states this)))
(defn ->state-map [entity-states]
  (reduce #(assoc %1 %2 (id->state %2)) {} entity-states))
(defn ->lctrl [this] (-> this ->state :lctrl))
(defn ->ctrlpt [this] (-> this ->state :ctrlpt))
(defn ->selection-box [this] (-> this ->state :selection-box))

(defn is-selected? [this id] (get (->selected this) id))
(defn click? [this] (= :click (->lctrl this)))
(defn drag? [this] (= :drag (->lctrl this)))

(defn create! [this name pos & {:as args}]
  (let [prefab   (prefab! name pos)
        id       (->id prefab)
        entity-states (->entity-states this)]
    (+state prefab
      (UpdateHook [this state] (sync-agent-velocity! this state))
      (UpdateHook [this state] (e/update! this state)))
    (e/start! prefab (keyword name) args)
    (parent! prefab this)
    (swat! this #(assoc % :entity-ids (conj entity-states id))))
  this)

(defn start-click! [this]
  (let [mpos (v2mpos)]
    (swat! this
     #(-> %
          (assoc :lctrl  :click)
          (assoc :ctrlpt mpos)))))

(defn half-dims [] (v2* (scrn-dims) 0.5))

(defn corners [ctrlpt1 ctrlpt2]
  (let [x1 (.x ctrlpt1) x2 (.x ctrlpt2)
        y1 (.y ctrlpt1) y2 (.y ctrlpt2)]
    [(v2 (<of x2 x1) (<of y2 y1))
     (v2 (>of x2 x1) (>of y2 y1))]))

(defn get-box-vecs [ctrlpt1 ctrlpt2]
  (let [[a b] (corners ctrlpt1 ctrlpt2)]
    [(v2- b a)
     (v2* (v2+ a b (v2- (scrn-dims))) 0.5)]))

(defn try-drag! [this]
  (let [ctrlpt2 (v2mpos)
        ctrlpt1 (->ctrlpt this)]
    (when (not= ctrlpt2 ctrlpt1)
      (swat! this #(assoc % :lctrl :drag))
      (let [[sd ap] (get-box-vecs ctrlpt1 ctrlpt2)
            selection-box (->selection-box this)]
        (set! (.anchoredPosition selection-box) ap)
        (set! (.sizeDelta selection-box) sd)))))

(defn reset-selection! [this]
  (set! (.sizeDelta (->selection-box this)) (v2 0 0)))

(defn click-over! [this]
  (reset-selection! this)
  (when-let [hit (mouse->hit controllable? (fn [_] false) :layer-mask (bit-shift-left 1 9))]
    (swat! this #(assoc % :selected-ids #{(->id hit)}))))

(defn selectbox-center [this]
  (scrn->worldpt
   (v2+
    (half-dims)
    (.anchoredPosition (->selection-box this)))))

(defn selectbox-dims [this]
  (let [rect (.rect (->selection-box this))
        hfdm (half-dims)
        p1 (v2+ hfdm (v2 (.x rect) (.y rect)))
        p2 (v2+ hfdm (v2 (.xMax rect) (.yMax rect)))]
     (v3- (scrn->worldpt p2)
          (scrn->worldpt p1))))

(defn drag-over! [this]
  (let [hits (boxcast (selectbox-center this)
                      (selectbox-dims this)
                      controllable?)
        entities (map #(.transform %) hits)
        owned-ids (into #{} (map ->id entities))]
    (reset-selection! this)
    (swat! this #(assoc % :selected-ids owned-ids))))

(defn mouse-up! [this]
  (case (->lctrl this)
    :click (click-over! this)
    :drag  (drag-over! this))
  (swat! this #(assoc % :lctrl nil)))

(defn handle-controls! [this]
  (cond
    (right-click)
    (when-let [hit (mouse->hit #(parent? this %)
                               (fn [_] true))]
      (swat! this #(assoc % :target hit)))
    (right-up) (swat! this #(assoc % :target nil))
    :else nil)
  (cond
    (left-click) (start-click! this)
    (left-held)  (try-drag! this)
    (left-up)    (mouse-up! this))
  this)

(defn update-entities! [this]
  (let [entity-states     (->entity-states this)
        i-map             (->state-map entity-states)
        new-entity-states (reduce update-map i-map entity-states)]
    (doseq [[id state] new-entity-states]
      (state! (->obj id) state))))

(defn retarget [entity-state id target selected? squad-pos]
  (if (and target selected? (:controllable entity-state))
    (let [final-target (or (get squad-pos id) target)]
      (assoc-in entity-state [:steering :destination] final-target))
    entity-state))

(defn sync-entities! [this]
  (let [target (->target this)
        selected (->selected this)
        squad-pos (box-squad selected target)]
   (doseq [entity (->entities this)]
     (let [id (->id entity)
           selected? (get selected id)
           new-entity-state
           (-> (->state entity)
               (assoc :position (position entity))
               (assoc :selected selected?)
               (retarget id target selected? squad-pos))]
       (state! entity new-entity-state))))
  this)

;; Hooks
(defn update! [this]
  "Update Hook for an RTS Controller"
  (-> this
      (handle-controls!)
      (sync-entities!)
      (update-entities!)))

(defn start! [this]
  "Start Hook for an RTS Controller"
  (state! this {:entity-ids #{}
                :target nil
                :selected-ids nil
                ;; TODO: Create the selection box inside here
                :selection-box (the "Selection Box" "RectTransform")
                :fow (the "FoW")}) 
  (-> this
      (create! "rat" (v3 -46.2 0. 0.)  :controllable true)
      (create! "minotaur" (v3 -5.2 0. 0.))))
