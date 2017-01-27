(ns wet.parser
  (:require [clojure.walk :as walk]
            [instaparse.core :as insta]
            [wet.filters :as filters]))

;; Parse tree nodes

(defrecord Assertion [operator operands])
(defrecord Assign [var value])
(defrecord Break [])
(defrecord Capture [var template])
(defrecord Case [val whens else])
(defrecord CollIndex [key])
(defrecord Condition [pred template])
(defrecord Continue [])
(defrecord Decrement [var])
(defrecord Else [template])
(defrecord Filter [name args])
(defrecord For [var collection for-opts template])
(defrecord ForLimit [val])
(defrecord ForOffset [val])
(defrecord ForOpts [limit offset reversed?])
(defrecord ForReversed [])
(defrecord If [conditions else])
(defrecord Increment [var])
(defrecord Lookup [name fns])
(defrecord ObjectExpr [obj filters])
(defrecord Operator [value])
(defrecord PredicateAnd [pred1 pred2])
(defrecord PredicateOr [pred1 pred2])
(defrecord Range [start end])
(defrecord Template [nodes])
(defrecord Unless [conditions else])
(defrecord When [val template])

;; Parsing

(def ^:private parse (insta/parser "resources/grammar.bnf"))

(defn- parse-template [& nodes] (->Template nodes))

(defn- parse-string [& nodes] (apply str nodes))

(defn- parse-condition-expr
  [constructor pred template & nodes]
  (let [conditions (->> nodes
                        (take-while (partial instance? Condition))
                        (cons (->Condition pred template)))
        else (when (instance? Else (last nodes)) (last nodes))]
    (constructor conditions else)))

(def ^:private parse-if (partial parse-condition-expr ->If))
(def ^:private parse-unless (partial parse-condition-expr ->Unless))

(defn- parse-case
  [val & nodes]
  (let [whens (take-while (partial instance? When) nodes)
        else (when (instance? Else (last nodes)) (last nodes))]
    (->Case val whens else)))

(defn- parse-assertion
  ([v] v)
  ([operand1 operator operand2]
   (->Assertion (:value operator) [operand1 operand2])))

(defn- parse-or
  ([pred] pred)
  ([pred1 pred2] (->PredicateOr pred1 pred2)))

(defn- parse-and
  ([pred] pred)
  ([pred1 pred2] (->PredicateAnd pred1 pred2)))

(defn- parse-object-expr [obj & filters] (->ObjectExpr obj filters))

(defn- parse-filter [name & args] (->Filter name args))

(defn- parse-inline-filter [name] (->Filter name nil))

(defn- parse-for-opts
  [& nodes]
  (letfn [(find-node [t] (first (filter (partial instance? t) nodes)))]
    (map->ForOpts {:limit (:val (find-node ForLimit))
                   :offset (:val (find-node ForOffset))
                   :reversed? (some? (find-node ForReversed))})))

(defn- parse-for
  ([var collection template]
   (parse-for var collection nil template))
  ([var collection for-opts template]
   (->For var collection for-opts template)))

(defn- parse-lookup [name & nodes] (->Lookup name nodes))

(def ^:private transformer
  {:template parse-template
   :b identity
   ;; Data types
   :int read-string
   :float read-string
   :bool read-string
   :strset identity
   :strescape read-string
   :string parse-string
   ;; Lookup
   :lookup parse-lookup
   :object-expr parse-object-expr
   :filter parse-filter
   :inline-filter parse-inline-filter
   :index ->CollIndex
   ;; Assignment
   :capture ->Capture
   :assign ->Assign
   :increment ->Increment
   :decrement ->Decrement
   ;; Conditions
   :operator ->Operator
   :assertion parse-assertion
   :predicate identity
   :else ->Else
   :elsif ->Condition
   :if parse-if
   :unless parse-unless
   :case parse-case
   :when ->When
   :and parse-and
   :or parse-or
   ;; Iteration
   :for parse-for
   :for-opts parse-for-opts
   :for-limit ->ForLimit
   :for-offset ->ForOffset
   :for-reversed ->ForReversed
   :break ->Break
   :continue ->Continue
   :range-start identity
   :range-end identity
   :range ->Range})

;; Evaluation

(declare eval-node)
(declare resolve-lookup)

(defn- resolve-range
  [{:keys [start end]} context opts]
  (let [resolver (fn [node]
                   (let [node* (if (instance? Lookup node)
                                 (resolve-lookup node context opts)
                                 node)]
                     ;; nil may occur under lax variable policy.
                     (or node* 0)))
        [start* end*] (map resolver [start end])]
    (if (> end* start*)
      (range start* (inc end*))
      (range start* (dec end*) -1))))

(defn- resolve-object
  [node context opts]
  (if-let [resolver (cond
                      (instance? Lookup node) resolve-lookup
                      (instance? Range node) resolve-range)]
    (resolver node context opts)
    node))

(defn- retrieve-filter
  "Looks for a Liquid filter, first in :custom-filters provided by user,
   then in standard filters library."
  [opts name]
  (or (get (:custom-filters opts) name) (filters/find-by-name name)))

(defn- apply-filter
  [{:keys [name args]} s context opts]
  (if-let [f (retrieve-filter opts name)]
    (->> args
         (map #(resolve-object % context opts))
         (apply (partial f s)))
    (if (:strict-filters? opts)
      (throw (ex-info (str "Undefined filter " name) {::undefined-filter name}))
      s)))

(defn- resolve-lookup
  [node context opts]
  (let [var-name (:name node)]
    (when (and (not (contains? (:params context) var-name))
               (:strict-variables? opts))
      (throw (ex-info (str "Undefined variable " var-name)
                      {::undefined-variable var-name})))
    (reduce
      (fn [res f]
        (cond
          (instance? Filter f) (apply-filter f res context opts)
          (instance? CollIndex f) (let [key (:key f)]
                                    (if (sequential? res)
                                      (when (integer? key) (get res key))
                                      (get (walk/stringify-keys res) key)))))
      (get-in context [:params var-name])
      (:fns node))))

(defn- resolve-object-expr
  [node context opts]
  (let [{:keys [obj filters]} node
        v (resolve-object obj context opts)]
    (try
      (reduce (fn [res f] (apply-filter f res context opts)) v filters)
      (catch Exception e
        ;; As in the original implementation,
        ;; when at least one filter in the filter chain is undefined,
        ;; a whole expression is rendered as nil
        (if (::undefined-filter (ex-data e))
          (when-not (:strict-filters? opts)
            (throw e)))))))

(defn- eval-assertion
  [node context opts]
  (let [{:keys [operator operands]} node
        operands* (map #(resolve-object % context opts) operands)]
    (case operator
      "==" (apply = operands*)
      "!=" (apply not= operands*)
      (">" ">=" "<" "<=") (if (every? number? operands*)
                            (apply (resolve (symbol operator)) operands*)
                            false)
      "contains" (cond
                   (every? string? operands*)
                   (.contains (first operands*) (second operands*))
                   (sequential? (first operands*))
                   (some? ((set (first operands*)) (second operands*)))
                   :else false))))

(defn- eval-predicate
  [node context opts]
  (letfn [(i? [t] (instance? t node))]
    (cond
      (i? Lookup) (resolve-lookup node context opts)
      (i? Assertion) (eval-assertion node context opts)
      (i? PredicateOr) (some #(eval-predicate % context opts)
                             [(:pred1 node) (:pred2 node)])
      (i? PredicateAnd) (every? #(eval-predicate % context opts)
                                [(:pred1 node) (:pred2 node)]))))

(defn- eval-condition-expr
  [node context opts]
  (let [conditions (:conditions node)
        eval-pred* (fn [n] (eval-predicate (:pred n) context opts))
        eval-template* (fn [n] (first (eval-node (:template n) context opts)))
        entry-holds? (cond-> (eval-pred* (first conditions))
                       (instance? Unless node) not)]
    (if entry-holds?
      (eval-template* (first conditions))
      (loop [conditions* (rest conditions)]
        (if-let [condition (first conditions*)]
          (if (eval-pred* condition)
            (eval-template* condition)
            (recur (rest conditions*)))
          (some-> (:else node) eval-template* first))))))

#_(defn- eval-if
  [node context opts]
  (loop [conditions (:conditions node)]
    (if-let [condition (first conditions)]
      (if (eval-predicate (:pred condition) context opts)
        (first (eval-node (:template condition) context opts))
        (recur (rest conditions)))
      (some-> (:else node) :template (eval-node context opts) first))))

(defn- eval-case
  [node context opts]
  (let [val (resolve-object (:val node) context opts)]
    (loop [whens (:whens node)]
      (if-let [when* (first whens)]
        (if (= val (resolve-object (:val when*) context opts))
          (first (eval-node (:template when*) context opts))
          (recur (rest whens)))
        (some-> (:else node) :template (eval-node context opts) first)))))

(defn- apply-for-opts
  [{:keys [limit offset reversed?]} coll]
  (cond->> coll
    offset (drop offset)
    limit (take limit)
    reversed? (reverse)))

(defn- eval-for
  [{:keys [var collection for-opts template]} context opts]
  (loop [res ""
         coll (->> (resolve-object collection context opts)
                   (apply-for-opts for-opts))]
    (if-let [item (first coll)]
      (let [item* (try
                    (first
                      (eval-node template
                                 (assoc-in context [:params var] item)
                                 opts))
                    (catch Exception e
                      (or (::iteration (ex-data e)) (throw e))))]
        (case item*
          ::break res
          ::continue (recur res (rest coll))
          (recur (str res item*) (rest coll))))
      res)))

(defn- update-counter
  [node context opts f]
  (try
    ["" (update-in context [:params (:var node)] f)]
    (catch Exception e
      (if (:strict-variables? opts)
        (throw e)
        ["" context]))))

(defmulti eval-node
  (fn [node _ _] (type node)))

(defmethod eval-node String
  [node context _]
  [node context])

(defmethod eval-node Template
  [node context opts]
  (reduce
    (fn [[res context*] node]
      (let [[node* context**] (eval-node node context* opts)]
        [(str res node*) context**]))
    ["" context]
    (:nodes node)))

(defmethod eval-node ObjectExpr
  [node context opts]
  [(resolve-object-expr node context opts) context])

(defmethod eval-node If
  [node context opts]
  [(eval-condition-expr node context opts) context])

(defmethod eval-node Unless
  [node context opts]
  [(eval-condition-expr node context opts) context])

(defmethod eval-node Case
  [node context opts]
  [(eval-case node context opts) context])

(defmethod eval-node For
  [node context opts]
  [(eval-for node context opts) context])

(defmethod eval-node Assign
  [node context opts]
  (let [[v _] (eval-node (:value node) context opts)]
    ["" (assoc-in context [:params (:var node)] v)]))

(defmethod eval-node Capture
  [node context opts]
  (let [[template _] (eval-node (:template node) context opts)]
    ["" (assoc-in context [:params (:var node)] template)]))

(defmethod eval-node Increment
  [node context opts]
  (update-counter node context opts inc))

(defmethod eval-node Decrement
  [node context opts]
  (update-counter node context opts dec))

(defmethod eval-node Break
  [_ _ _]
  (throw (ex-info nil {::iteration ::break})))

(defmethod eval-node Continue
  [_ _ _]
  (throw (ex-info nil {::iteration ::continue})))

(defn- transform [template] (insta/transform transformer template))

(def parse-and-transform (comp transform parse))

(defn eval-template
  [node context opts]
  (eval-node node context opts))
