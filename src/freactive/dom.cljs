(ns freactive.dom
  (:require
   [freactive.core :as r]
   [freactive.ui-common :as ui]
   [goog.object]))

;; ## Polyfills

(def request-animation-frame
  (or
   (.-requestAnimationFrame js/window)
   (.-webkitRequestAnimationFrame js/window)
   (.-mozRequestAnimationFrame js/window)
   (.-msRequestAnimationFrame js/window)
   (.-oRequestAnimationFrame js/window)
   (let [t0 (.getTime (js/Date.))]
     (fn [f]
       (js/setTimeout
        #(f (- (.getTime (js/Date.)) t0))
        16.66666)))))

;; Render Loop

(defonce ^:private render-queue #js [])

(def ^:private enable-fps-instrumentation false)

(defn enable-fps-instrumentation!
  ([] (enable-fps-instrumentation! true))
  ([enable] (set! enable-fps-instrumentation enable)))

(def ^:private instrumentation-i -1)

(def ^:private last-instrumentation-time)

(defonce fps (r/atom nil))

(defonce frame-time (r/atom nil))

(def ^:private *animating* nil)

(defonce
  render-loop
  (request-animation-frame
    (fn render[frame-ms]
      (binding [*animating* true]
        (reset! frame-time frame-ms)
        (when enable-fps-instrumentation
          (if (identical? instrumentation-i 14)
            (do
              (reset! fps (* 1000 (/ 15 (- frame-ms last-instrumentation-time))))
              (set! instrumentation-i 0))
            (set! instrumentation-i (inc instrumentation-i)))
          (when (identical? 0 instrumentation-i)
            (set! last-instrumentation-time frame-ms)))
        (let [queue render-queue
              n (alength queue)]
          (when (> n 0)
            (set! render-queue #js [])
            (loop [i 0]
              (when (< i n)
                ((aget queue i))
                (recur (inc i)))))))
      (request-animation-frame render))))

(defn queue-animation [f]
  (if *animating*
    (f)
    (.push render-queue f)))

;; ## Attributes, Styles & Events

;; TODO lifecycle callbacks

;; (defn- on-moving [state node cb]
;;   (if-let [node-moving (get-state-attr state ":node/on-moving")]
;;     (node-moving node cb)
;;     (cb)))

;; (defn- on-moved [state node]
;;   (when-let [node-moved (get-state-attr state ":node/on-moved")]
;;     (node-moved node)))

(defn update-attrs
  "Convenience function to update the attrs in a virtual dom vector.
Works like Clojure's update function but f (and its args) only modify the attrs
map in velem."
  ([velem f & args]
   (let [tag (first velem)
         attrs? (second velem)
         attrs (when (map? attrs?) attrs?)]
     (concat [tag (apply f attrs args)] (if attrs (nnext velem) (next velem))))))

;; Plugin Support

(def ^:private node-ns-lookup
  #js
  {:svg "http://www.w3.org/2000/svg"})

(defn register-node-prefix! [prefix xml-ns-or-handler]
  (aset node-ns-lookup prefix xml-ns-or-handler))

(def ^:private attr-ns-lookup
  #js
  {:node (fn [_ _ v] v)
   :state (fn [_ _ v] v)})

(defn register-attr-prefix! [prefix xml-ns-or-handler]
  (aset attr-ns-lookup prefix xml-ns-or-handler))

;; Core DOM stuff

(defn- get-element-state [x]
  (.-freactive-state x))

(defn- dom-insert [parent dom-node vnext-sibling]
  (let [dom-parent (ui/native-parent parent)]
    ;; (when vnext-sibling (println "vnext" (type vnext-sibling)))
    (if-let [dom-before (ui/next-native-sibling vnext-sibling)] 
      (do
        ;; (println "insert" (goog/getUid dom-node)
        ;;          "dom-parent" dom-parent (goog/getUid dom-parent)
        ;;          "dom-before" dom-before (goog/getUid dom-before))
        (.insertBefore dom-parent dom-node dom-before))
      (do
        ;; (println "append" (goog/getUid dom-node)
        ;;          "dom-parent" dom-parent (goog/getUid dom-parent))
        (.appendChild dom-parent dom-node)))))

(defn- dom-remove [dom-node]
  (when-let [parent (.-parentNode dom-node)]
    (.removeChild parent dom-node)))

(defn- dom-simple-replace [new-node old-node]
  (when-let [parent (.-parentNode old-node)]
    (.replaceChild parent new-node old-node)))

(defn- dom-remove-replace [new-node old-velem]
  (let [parent (ui/velem-parent old-velem)
        next-sib (ui/velem-next-sibling-of parent old-velem)]
    (ui/velem-remove old-velem)
    (dom-insert (ui/native-parent parent) new-node next-sib)))

(deftype UnmanagedDOMNode [node on-dispose ^:mutable parent]
  Object
  (dispose [this]
    (when on-dispose
      (on-dispose this)))

  ui/IVirtualElement
  (-velem-parent [this] parent)
  (-velem-head [this] this)
  (-velem-tail [this] this)
  (-velem-next-sibling-of [this child])
  (-velem-native-element [this] node)
  (-velem-simple-element [this] this)
  (-velem-insert [this vparent vnext-sibling]
    (set! parent vparent)
    (dom-insert vparent node vnext-sibling)
    this)
  (-velem-take [this]
    (dom-remove node))
  (-velem-remove [this]
    (dom-remove node))
  (-velem-replace [this old-velem]
    (if-let [old-node (ui/velem-native-element old-velem)]
      (dom-simple-replace node old-node)
      (dom-remove-replace node old-velem))
    this)
  (-velem-lifecycle-callback [this cb-name]))

;; Managed DOMElement stuff

(defprotocol IDOMAttrValue
  (-get-attr-value [value]))

(defn- normalize-attr-value [value]
  (cond
    (.-substring value) value
    (keyword? value) (name value)
    (identical? value true) ""
    (identical? value false) nil
    (satisfies? IDOMAttrValue value) (-get-attr-value value)
    :default (.toString value)))

(defn- set-attr! [element attr-name attr-value]
  (if attr-value
    (.setAttribute element attr-name (normalize-attr-value attr-value))
    (.removeAttribute element attr-name)))

(defn- set-style! [elem prop-name prop-value]
  ;(println "set-style-prop!" elem prop-name prop-value)
  (if prop-value
    (aset (.-style elem) prop-name (normalize-attr-value prop-value))
    (js-delete (.-style elem) prop-name))
  prop-value)

(defn ^:dynamic ^:pluggable listen!
  "Adds an event handler. Can be replaced by a plugin such as goog.events."
  [element evt-name handler]
  (.addEventListener element evt-name handler false))

(defn ^:dynamic ^:pluggable unlisten!
  "Removes an event handler. Can be replaced by a plugin such as goog.events."
  [element evt-name handler]
  (.removeEventListener  element evt-name handler))

(deftype EventBinding [node event-name ^:mutable handler]
  IFn
  (-invoke [this new-handler]
    (.unlisten this)
    (set! handler new-handler)
    (when handler
      (listen! node event-name handler))
    this)
  Object
  (unlisten [this]
    (when handler
      (unlisten! node event-name handler)))
  (dispose [this]
    (.unlisten this)
    (set! (.-disposed this) true)))

(defn- bind-event! [node event-name handler]
  ((EventBinding. node (name event-name) nil) handler))

(defn- do-bind-attr [setter val]
  (r/bind-attr* val setter queue-animation))

(defn- bind-style! [node style-kw style-val]
  (let [style-name (name style-kw)]
    (do-bind-attr (fn [val] (set-style! node style-name val)) style-val)))

(defn- do-set-data-state! [element state]
  (set-attr! element "data-state" state))

(defn- get-data-state [element]
  (.getAttribute element "data-state"))

(defn- get-state-attr [state attr-str]
  (when-let [attrs (when state (.-attrs state))]
    (aget attrs attr-str)))

(defn- set-data-state!
  ([element state]
    (let [cur-state (get-data-state element)
          node-state (get-element-state element)
          state (when state (name state))]
      (when-not (identical? cur-state state)
        (do-set-data-state! element state)
        (when-let [enter-transition (get-state-attr node-state (str ":state/on-" state))]
          (enter-transition element cur-state state))))))

(def ^:private attr-setters
  #js
  {:data-state (fn [element state]
                 (set-data-state! element state)
                 state)
   :class (fn [element cls]
            (set! (.-className element) cls)
            cls)})

;; attributes to set directly
(doseq [a #js ["id" "value"]]
  (aset attr-setters a (fn [e v] (aset e a v))))

(defn- get-attr-setter [element attr-name]
  (if-let [setter (aget attr-setters attr-name)]
    (fn [attr-value] (setter element attr-value))
    (fn [attr-value]
      (set-attr! element attr-name attr-value))))

;; TODO
(defn- bind-lifecycle-callback! [node node-state cb-name cb-value]
  (when (identical? cb-name "on-disposed")
    (set! (.-disposed-callback node-state) cb-value)
    ;; other callbacks automatically included in attr map
    )
  cb-value)

(defn- get-ns-attr-setter [element attr-ns attr-name]
  (fn [attr-value]
      ;(println "setting attr" element attr-name attr-value)
      (if attr-value
        (.setAttributeNS element attr-ns attr-name
          (normalize-attr-value attr-value))
        (.removeAttributeNS element attr-ns attr-name))
      attr-value))

(defn- ->str [kw-or-str]
  (if (string? kw-or-str)
    kw-or-str
    (if-let [attr-ns (namespace kw-or-str)]
      (str attr-ns "/" (name kw-or-str))
      (name kw-or-str))))

(defn- dispose-states [states]
  (goog.object/forEach
   states
   (fn [state k _]
     (when state
       (r/dispose state)))))

(defn- attr-diff* [node oas attr-map bind-attr]
  (let [nas #js {}]
    (doseq [[k v] attr-map]
      (let [kstr (->str k)]
        (if-let [existing (when oas (aget oas kstr))]
          (do
            (js-delete oas kstr)
            (when-let [new-state (existing v)]
              (aset nas kstr new-state)))
          (aset nas kstr (bind-attr node k v)))))
    (dispose-states oas)
    nas))

(defn- bind-styles! [node old-state style-map]
  (attr-diff* node old-state style-map bind-style!))

(defn- bind-events! [node old-state evt-map]
  (attr-diff* node old-state evt-map bind-event!))

(defn- bind-attr! [element attr-key attr-value]
  (let [attr-ns (namespace attr-key)
        attr-name (name attr-key)]
    (if attr-ns
      (if-let [attr-handler (aget attr-ns-lookup attr-ns)]
        (cond
          (string? attr-handler)
          (do-bind-attr (get-ns-attr-setter element attr-ns attr-name) attr-value)

          (fn? attr-handler)
          (attr-handler element attr-name attr-value)

          :default
          (.warn js/console "Invalid ns attr handler" attr-handler))
        (.warn js/console "Undefined ns attr prefix" attr-ns))
      (cond
        (identical? 0 (.indexOf attr-name "on-"))
        (bind-event! element (.substring attr-name 3) attr-value)

        :default
        (do-bind-attr (get-attr-setter element attr-name) attr-value)))))


(deftype DOMElement [ns-uri tag ^:mutable attrs children ^:mutable node
                     ^:mutable parent ^:mutable attr-states ^:mutable events
                     ^:mutable styles]
  IHash
  (-hash [this] (goog/getUid this))

  Object
  (dispose [this]
    (dispose-states attr-states)
    (dispose-states events)
    (dispose-states styles)
    (doseq [i (range (.-length children))]
      (r/dispose (aget children i))))
  (ensureNode [this]
    (when-not node
      (set! node
            (if ns-uri
              (.createElementNS js/document ns-uri tag)
              (.createElement js/document tag)))
      (set! (.-freactive-state node) this)
      (.updateAttrs this attrs)
      (doseq [child children]
        (ui/velem-insert child this nil))))
  (onAttached [this]
    (when-let [on-attached (get attrs :node/on-attached)]
      (on-attached node)))
  (updateAttrs [this new-attrs]
    (let [{new-events :events new-style :style :as new-attrs} new-attrs]
      (set! events (bind-events! node events new-events))
      (set! styles (bind-styles! node styles new-style))
      (set! attr-states (attr-diff* node attr-states (dissoc new-attrs :events :style) bind-attr!))
      (set! attrs new-attrs)))

  ui/IVirtualElement
  (-velem-parent [this] parent)
  (-velem-head [this] this)
  (-velem-tail [this] this)
  (-velem-next-sibling-of [this child]
    (ui/array-next-sibling-of children child))
  (-velem-native-element [this] node)
  (-velem-simple-element [this] this)
  (-velem-insert [this vparent vnext-sibling]
    (set! parent vparent)
    (.ensureNode this)
    (dom-insert parent node vnext-sibling)
    (.onAttached this)
    this)
  (-velem-take [this]
    (dom-remove node))
  (-velem-remove [this]
    (.dispose this)
    (dom-remove node))
  (-velem-replace [this old-velem]
    (.ensureNode this)
    (if-let [old-node (ui/velem-native-element old-velem)]
      (do
        (dom-simple-replace node old-node)
        (r/dispose old-velem))
      (dom-remove-replace node old-velem))
    (.onAttached this)
    this)
  (-velem-lifecycle-callback [this cb-name]
    (get attrs cb-name)))

(defn- text-node? [dom-node]
  (identical? (.-nodeType dom-node) 3))

(deftype DOMTextNode [^:mutable text ^:mutable node ^:mutable parent]
  Object
  (ensureNode [this]
    (when-not node
      (set! node (.createTextNode js/document text))))
  ui/IVirtualElement
  (-velem-parent [this] parent)
  (-velem-head [this] this)
  (-velem-tail [this] this)
  (-velem-next-sibling-of [this child])
  (-velem-native-element [this]
    (.ensureNode this)
    node)
  (-velem-simple-element [this] this)
  (-velem-insert [this vparent vnext-sibling]
    (.ensureNode this)
    (set! parent vparent)
    (dom-insert parent node vnext-sibling)
    this)
  (-velem-take [this]
    (dom-remove node))
  (-velem-remove [this]
    (dom-remove node))
  (-velem-replace [this old-velem]
    (if-let [old-node (ui/velem-native-element old-velem)]
      (do
        (r/dispose old-node)
        (if (text-node? old-node)
          (do
            (set! node old-node)
            (set! (.-textContent node) text))
          (do
            (.ensureNode this)
            (dom-simple-replace node old-node))))
      (do
        (.ensureNode this)
        (dom-remove-replace node old-velem)))
    this)
  (-velem-lifecycle-callback [this cb-name]))

;; Conversion of Clojure(script) DOM images to virtual DOM

(defprotocol IDOMImage
  "A protocol for things that can be represented as virtual DOM or contain DOM nodes.

Can be used to define custom conversions to DOM nodes or text for things such as numbers
or dates; or can be used to define containers for DOM elements themselves."
  (-get-dom-image [x]
    "Should return either virtual DOM (a vector or string) or an actual DOM node."))

(extend-protocol IDOMImage
  nil
  (-get-dom-image [x] "")

  boolean
  (-get-dom-image [x] (str x))

  number
  (-get-dom-image [x] (str x)))

;; From hiccup.compiler:
(def ^{:doc "Regular expression that parses a CSS-style id and class from an element name."
       :private true}
     re-tag #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

(def ^:private re-dot (js/RegExp. "\\." "g"))

(declare as-velem)

(defn- append-children* [ch* children]
  (doseq [ch children]
    (if (sequential? ch)
      (let [head (first ch)]
        (cond
          (or (keyword? head) (fn? head))
          (.push ch* (as-velem ch))

          :default
          (append-children* ch* ch)))
      (.push ch* (as-velem ch))))
  ch*)

(defn- dom-element [ns-uri tag tail]
  (let [[_ tag-name id class] (re-matches re-tag tag)
        attrs? (first tail)
        have-attrs (map? attrs?)
        attrs (if have-attrs attrs? {})
        attrs (cond-> attrs

                (and id (not (:id attrs)))
                (assoc :id id)
                
                class
                (update :class
                        (fn [cls]
                          (let [class (.replace class re-dot " ")]
                            (if cls (str class " " cls) class)))))

        children (if have-attrs (rest tail) tail)
        children* (append-children* #js [] children)]
    (DOMElement. ns-uri tag-name attrs children* nil nil nil nil nil)))

(defn- dom-node? [x]
  (and x (> (.-nodeType x) 0)))

(defn managed? [elem]
  (if (get-element-state elem) true false))

(defn- ensure-unmanaged [elem]
  (assert (dom-node? elem))
  (assert (not (managed? elem)))
  elem)

(defn wrap-unmanaged-node [dom-node on-dispose]
  (ensure-unmanaged dom-node)
  (let [state (UnmanagedDOMNode. dom-node on-dispose nil)]
    (set! (.-freactive-state dom-node) state)
    state))

(defn- as-velem [elem-spec]
  (cond
    (string? elem-spec)
    (DOMTextNode. elem-spec nil nil)

    (dom-node? elem-spec)
    (if-let [state (get-element-state elem-spec)]
      state
      (UnmanagedDOMNode. elem-spec nil nil))

    (vector? elem-spec)
    (let [tag (first elem-spec)]
      (cond
        (keyword? tag)
        (let [tag-ns (namespace tag)
              tag-name (name tag)
              tail (rest elem-spec)]
          (if tag-ns
            (if-let [tag-handler (aget node-ns-lookup tag-ns)]
              (cond
                (string? tag-handler)
                (dom-element tag-handler tag-name tail)

                (fn? tag-handler)
                (as-velem (tag-handler tag-name tail))

                :default
                (.warn js/console "Invalid ns node handler" tag-handler))
              (.warn js/console "Undefined ns node prefix" tag-ns))
            (dom-element nil tag-name tail)))

        :default
        (assert false "Only know how to handle keyword tags")))

    (satisfies? IDeref elem-spec)
    (ui/reactive-element elem-spec as-velem queue-animation)

    (satisfies? ui/IVirtualElement elem-spec)
    elem-spec

    (satisfies? r/IReactiveProjection elem-spec)
    (ui/reactive-element-collection elem-spec as-velem queue-animation)

    :default (as-velem (-get-dom-image elem-spec))))

;; Public API

(defn- get-velem-state [elem]
  (or (get-element-state elem) elem))

(defn- get-managed-dom-element [elem]
  (let [velem (ui/velem-simple-element (get-element-state elem))]
    (assert (instance? DOMElement velem) "Not a managed DOM element.")
    velem))

(defn set-attrs!
  "Sets the attributes on a managed element to the new-attrs map
(unsetting previous values if not present in the new map)."
  [elem new-attrs]
  (let [velem (get-managed-dom-element elem)]
    (.updateAttrs velem new-attrs)))

(defn merge-attrs! [elem attrs]
  "Merges the attrs map with a managed element's existing attribute map. A nil
value will unset any attribute."
  (let [velem (get-managed-dom-element elem)]
    (.updateAttrs velem (merge (.-attrs velem) attrs))))

(defn update-attrs! [elem f & args]
  "Updates a managed element's attributes by applying f and optionally args to
the existing attribute map."
  (let [velem (get-managed-dom-element elem)]
    (.updateAttrs velem (apply f (.-attrs velem) args))))

(defn- find-by-id [id]
  (.getElementById js/document id))

(defn- create-or-find-root-node [id]
  (if-let [root-node (find-by-id id)]
    root-node
    (let [root-node (.createElement js/document "div")]
      (set! (.-id root-node) id)
      (.appendChild (.-body js/document) root-node))))

(defn- configure-root! [vroot vdom]
  (let [root (ui/velem-insert (as-velem vdom) vroot nil)]
    (set! (.-root vroot) root)
    (set! (.-on-dispose vroot) (fn [] (r/dispose root)))))

(defn- do-unmount! [vroot]
  (let [root (.-root vroot)]
    (when-not (= :unmounted root)
      (ui/velem-remove root)
      (set! (.-root vroot) :unmounted))))

(defn mount! [mount-point vdom]
  "Makes the specified mount-point the root of a managed element tree, replacing
all of its content with the managed content specified by vdom.
mount-point may be an unmanaged DOM element or a string specifying the id of one.
If a string id is passed and no matching node is found, one will be appended to the
document body."
  (let [root-node
        (cond
          (dom-node? mount-point)
          mount-point

          (string? mount-point)
          (create-or-find-root-node mount-point))]
    (if-let [vroot (get-element-state root-node)]
      (do
        (assert (.-root vroot) "Can only remount at a previous mount point")
        (do-unmount! vroot)
        (configure-root! vroot vdom))
      (do
        (loop []
          (let [last-child (.-lastChild root-node)]
            (when last-child
              (ui/velem-remove (as-velem (get-velem-state last-child)))
              (recur))))
        (let [vroot (wrap-unmanaged-node root-node nil)]
          (configure-root! vroot vdom))))
    root-node)) 

(defn unmount! [mount-point]
  (let [root-node
        (cond
          (dom-node? mount-point)
          mount-point

          (string? mount-point)
          (find-by-id mount-point))
        vroot (get-element-state root-node)]
    (assert (and root-node vroot (.-root vroot)) "Can't find mount point")
    (do-unmount! vroot)
    root-node))
