(ns cmr.search.services.parameters.converters.attribute
  "Contains functions for converting additional attribute search parameters to a query model"
  (:require [cmr.search.models.query :as qm]
            [cmr.search.models.group-query-conditions :as gc]
            [cmr.search.services.parameters.conversion :as p]
            [clojure.string :as str]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [cmr.search.services.messages.attribute-messages :as msg]
            [cmr.common.services.errors :as errors]
            [cmr.common.date-time-parser :as date-time-parser])
  (:import [cmr.search.models.query
            AttributeNameCondition
            AttributeValueCondition
            AttributeRangeCondition]
           clojure.lang.ExceptionInfo))

(defn empty->nil
  "Converts an empty string to nil"
  [s]
  (when-not (empty? s)
    s))

(defn value->condition
  "Parses an additional attribute value into it's constituent parts.
  Values must be comma separated in one of the following formats:
  * name
  * type,name,value
    * Example: \"string,fav_color,blue\"
  * type,name,min,max
    * Example: \"float,cloud_cover_range,0,100\"
    * Example: \"float,cloud_cover_range,,80\"  means must be less than 80 with no lower bounds
    * Example: \"float,cloud_cover_range,10,\"  means must be greater than 10 with no upper bounds"
  [value]
  (let [comma-escape "\\,"
        comma-replace "%COMMA%" ; used to replace escaped commas during splitting
        parts (-> value
                  (str/replace comma-escape comma-replace)
                  (str/split #"," 5))
        parts (->> parts
                   (map #(str/replace % comma-replace ","))
                   (map empty->nil))
        attr-name (if (= 1 (count parts))
                    (first parts)
                    (nth parts 1))
        [attr-type _ minv maxv] parts]

    (if attr-name
      (case (count parts)
        1
        (qm/map->AttributeNameCondition {:name attr-name})
        3
        (qm/map->AttributeValueCondition {:type attr-type
                                          :name attr-name
                                          :value minv})
        4
        (qm/map->AttributeRangeCondition {:type attr-type
                                          :name attr-name
                                          :min-value minv
                                          :max-value maxv})
        ;; else
        {:errors [(msg/invalid-num-parts-msg)]})
      {:errors [(msg/invalid-name-msg attr-name)]})))

(def attribute-type->parser-fn
  "A map of attribute types to functions that can parse a value"
  {:string identity
   :float #(Double/parseDouble %)
   :int #(Integer/parseInt %)
   :datetime date-time-parser/parse-datetime
   :time date-time-parser/parse-time
   :date date-time-parser/parse-date})

(defmulti parse-condition-values
  "Parses the component type into their expected values"
  (fn [condition]
    (type condition)))

(defn parse-field
  "Attempts to parse the given field and update the condition. If there are problems parsing an
  errors attribute will be returned."
  [condition field parser type]
  (let [handle-exception #(update-in condition [:errors]
                                     conj (msg/invalid-value-msg type (get condition field)))]
    (try
      (update-in condition [field] parser)
      (catch NumberFormatException e
        (handle-exception))
      (catch ExceptionInfo e
        (handle-exception)))))

(defmethod parse-condition-values AttributeRangeCondition
  [condition]
  (let [{:keys [type min-value max-value]} condition]
    (if (or min-value max-value)
      (let [parser (attribute-type->parser-fn type)
            parser #(when % (parser %))
            condition (-> condition
                          (parse-field :min-value parser type)
                          (parse-field :max-value parser type))]
        (if (:errors condition)
          {:errors (:errors condition)}
          condition))
      {:errors [(msg/one-of-min-max-msg)]})))

(defmethod parse-condition-values AttributeValueCondition
  [condition]
  (let [{:keys [type value]} condition]
    (if (:value condition)
      (let [parser (attribute-type->parser-fn type)
            condition (parse-field condition :value parser type)]
        (if (:errors condition)
          {:errors (:errors condition)}
          condition))
      {:errors [(msg/invalid-value-msg type value)]})))

(defmulti parse-component-type
  "Parses the type and values of the given condition. Returns the condition with values updated with
  the parsed value or error message added to its :errors field in case of validation failures."
  (fn [condition]
    (type condition)))

(defmethod parse-component-type AttributeNameCondition
  [condition]
  condition)

(defmethod parse-component-type :default
  [condition]
  (if-let [type (some (set qm/attribute-types) [(keyword (:type condition))])]
    (parse-condition-values (assoc condition :type type))
    {:errors [(msg/invalid-type-msg (:type condition))]}))

(defn parse-value
  "Parses an additional attribute value into it's constituent parts"
  [value]
  (let [condition (value->condition value)]
    (if (:errors condition)
      condition
      (parse-component-type condition))))

;; Converts parameter and values into collection query condition
(defmethod p/parameter->condition :attribute
  [concept-type param values options]
  (let [conditions (map parse-value values)
        failed-conditions (seq (filter :errors conditions))
        _ (when failed-conditions
            (errors/internal-error!
              (format
                "Found invalid value that should have been validated already. Values: %s"
                (pr-str values))))
        operator (if (= "true" (get-in options [:attribute :or]))
                   :or
                   :and)
        attrib-condition (gc/group-conds operator conditions)
        exclude-collection (= "true" (get-in options [:attribute :exclude-collection]))]

    (if (and (= :granule concept-type) (not exclude-collection))
      ;; Granule attribute queries will inherit values from their parent collections
      ;; unless :exclude-collection option is set to true.
      (gc/or-conds [attrib-condition (qm/->CollectionQueryCondition attrib-condition)])
      attrib-condition)))

