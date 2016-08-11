(ns cmr.umm-spec.test.dif10-expected-conversion
 "DIF 10 specific expected conversion functionality"
 (:require [clj-time.core :as t]
           [clj-time.format :as f]
           [cmr.umm-spec.util :as su]
           [cmr.umm-spec.json-schema :as js]
           [cmr.common.util :as util :refer [update-in-each]]
           [cmr.umm-spec.models.common :as cmn]
           [cmr.umm-spec.test.expected-conversion-util :as conversion-util]
           [cmr.umm-spec.related-url :as ru-gen]
           [cmr.umm-spec.location-keywords :as lk]
           [cmr.umm-spec.test.location-keywords-helper :as lkt]
           [cmr.umm-spec.models.collection :as umm-c]
           [cmr.umm-spec.umm-to-xml-mappings.dif10 :as dif10]
           [cmr.umm-spec.xml-to-umm-mappings.dif10.data-contact :as contact]))

(def dif10-roles
 {"Technical Contact" "Technical Contact"
  "Investigator" "Investigator"
  "Metadata Author" "Metadata Author"})

(defn- filter-roles
 "Filter roles that are not applicable to DIF 10. Default is Technical Contact"
 [roles]
 (distinct
  (map
   #(get dif10-roles % "Technical Contact") roles)))

(defn- dif10-platform
  [platform]
  ;; Only a limited subset of platform types are supported by DIF 10.
  (assoc platform :Type (get dif10/platform-types (:Type platform))))

(defn- dif10-processing-level
  [processing-level]
  (-> processing-level
      (assoc :ProcessingLevelDescription nil)
      (assoc :Id (get dif10/product-levels (:Id processing-level)))
      su/convert-empty-record-to-nil))

(defn- dif10-project
  [proj]
  (-> proj
      ;; DIF 10 only has at most one campaign in Project Campaigns
      (update-in [:Campaigns] #(when (first %) [(first %)]))
      ;; DIF10 StartDate and EndDate are date rather than datetime
      (update-in [:StartDate] conversion-util/date-time->date)
      (update-in [:EndDate] conversion-util/date-time->date)))

(defn- filter-dif10-metadata-associations
  "Removes metadata associations with type \"LARGER CITATIONS WORKS\" since this type is not
  allowed in DIF10."
  [mas]
  (seq (filter #(not= (:Type %) "LARGER CITATION WORKS")
               mas)))

(defn- fix-dif10-matadata-association-type
  "Defaults metadata association type to \"SCIENCE ASSOCIATED\"."
  [ma]
  (update-in ma [:Type] #(or % "SCIENCE ASSOCIATED")))

(defn- expected-dif10-related-urls
  [related-urls]
  (seq (for [related-url related-urls]
         (assoc related-url :Title nil :FileSize nil :MimeType nil))))

(defn- expected-dif10-spatial-extent
  [spatial-extent]
  (-> spatial-extent
      (update-in [:HorizontalSpatialDomain :Geometry] conversion-util/geometry-with-coordinate-system)
      (update-in-each [:HorizontalSpatialDomain :Geometry :GPolygons] conversion-util/fix-echo10-dif10-polygon)
      conversion-util/prune-empty-maps))

(defn- expected-dif10-contact-mechanisms
  "Returns the expected DIF contact mechanisms"
  [contact-mechanisms]
  (->> (concat (filter #(= "Email" (:Type %)) contact-mechanisms)
               (filter
                #(and (not= "Email" (:Type %))
                      (not= "Twitter" (:Type %))
                      (not= "Facebook" (:Type %)))
                contact-mechanisms))
       seq))

(defn- expected-dif10-contact-information
  "Retruns the expected contact information for the given contact information."
  [contact-info]
  (let [contact-info (-> contact-info
                         (dissoc :ServiceHours)
                         (dissoc :RelatedUrls)
                         (dissoc :ContactInstruction)
                         (update :ContactMechanisms expected-dif10-contact-mechanisms)
                         (update :Addresses conversion-util/expected-dif-addresses))]
    (when (seq (util/remove-nil-keys contact-info))
      (cmn/map->ContactInformationType contact-info))))

(defn- expected-dif10-contact-info-urls
  "Returns a vector of the first URL in the list"
  [urls]
  (when (seq urls)
    [(first urls)]))

(defn- expected-dif-10-contact-info-related-urls
  "Returns the expected DIF 10 RelatedURL for the ContactInformation
   or nil if there are no related urls"
  [related-urls]
  (let [related-url (first related-urls)]
    (if related-url
     [(-> related-urls
          (first)
          (dissoc :Description)
          (dissoc :MimeType)
          (dissoc :Relation)
          (dissoc :Title)
          (dissoc :FileSize)
          (update :URLs expected-dif10-contact-info-urls)
          (cmn/map->RelatedUrlType))]
     nil)))

(defn- expected-dif10-data-center-contact-information
  "Returns the expected DIF10 ContactInformation for the data center.
   If all fields are nil, return nil. Data Center contact infos do not have Contact Mechanisms
   or Addresses"
  [contact-info]
  (if (and (nil? (:ServiceHours contact-info))
           (nil? (:ContactInstruction contact-info))
           (nil? (:RelatedUrls contact-info)))
    nil
    (let [contact-info
          (-> contact-info
              (update :RelatedUrls expected-dif-10-contact-info-related-urls)
              (dissoc :ContactMechanisms)
              (dissoc :Addresses))]
      (if (seq (util/remove-nil-keys contact-info))
        (cmn/map->ContactInformationType contact-info)
        contact-info))))

(defn- contact->expected-dif10-collection
  "Return the expected contact person or contact group for the DIF 10 collection, not associated
   with a data center"
  [contact]
  (-> contact
      (assoc :NonDataCenterAffiliation nil)
      (update :Roles filter-roles)
      (update :ContactInformation expected-dif10-contact-information)))

(defn- contact->expected-dif10-data-center
  "Return the expected contact person or contact group for the DIF 10 data center"
  [contact]
  (-> contact
      (assoc :NonDataCenterAffiliation nil)
      (assoc :Roles [contact/dif10-data-center-personnel-role])
      (update :ContactInformation expected-dif10-contact-information)))

(defn- expected-dif10-data-center-contacts
  "Returns the expected DIF 10 data center contact persons or contact groups for the given UMM data center."
  [contacts]
  (let [expected-contacts (mapv #(contact->expected-dif10-data-center %) contacts)]
    (when (seq expected-contacts)
      expected-contacts)))

(defn- expected-dif10-contacts
  [contacts]
  "Returns the expected DIF 10 data center contact persons or contact groups for the given UMM collection."
  (let [expected-contacts (mapv #(contact->expected-dif10-collection %) contacts)]
    (when (seq expected-contacts)
      expected-contacts)))

(defn- data-center->expected-dif10
  "Returns the expected DIF 10 data center. DIF 10 requires a personnel record on the data center.
   If there are no contact groups or contact persons, create a dummy contact person on the data center"
  [data-center]
  (let [data-center (update data-center :ContactInformation expected-dif10-data-center-contact-information)]
   (if (or (seq (:ContactGroups data-center)) (seq (:ContactPersons data-center)))
    (-> data-center
       (update :ContactPersons expected-dif10-data-center-contacts)
       (update :ContactGroups expected-dif10-data-center-contacts))
    (assoc data-center :ContactPersons [(cmn/map->ContactPersonType {:Roles [contact/dif10-data-center-personnel-role]
                                                                     :LastName su/not-provided})]))))

(defn- expected-dif10-data-centers
  "Returns the list of expected DIF 10 data centers"
  [data-centers]
  (seq (mapv #(data-center->expected-dif10 %) data-centers)))

(defn- expected-dif10-additional-attribute
  [attribute]
  (-> attribute
      (assoc :Group nil)
      (assoc :UpdateDate nil)
      (assoc :MeasurementResolution nil)
      (assoc :ParameterUnitsOfMeasure nil)
      (assoc :ParameterValueAccuracy nil)
      (assoc :ValueAccuracyExplanation nil)
      (assoc :Description (su/with-default (:Description attribute)))))

(defn umm-expected-conversion-dif10
  [umm-coll]
  (-> umm-coll
      (update-in [:MetadataAssociations] filter-dif10-metadata-associations)
      (update-in-each [:MetadataAssociations] fix-dif10-matadata-association-type)
      (update-in [:DataCenters] expected-dif10-data-centers)
      (update-in [:ContactGroups] expected-dif10-contacts)
      (update-in [:ContactPersons] expected-dif10-contacts)
      (update-in [:SpatialExtent] expected-dif10-spatial-extent)
      (update-in [:DataDates] conversion-util/fixup-dif10-data-dates)
      (update-in [:Distributions] su/remove-empty-records)
      (update-in-each [:Platforms] dif10-platform)
      (update-in-each [:AdditionalAttributes] expected-dif10-additional-attribute)
      (update-in [:ProcessingLevel] dif10-processing-level)
      (update-in-each [:Projects] dif10-project)
      (update-in [:PublicationReferences] conversion-util/prune-empty-maps)
      (update-in-each [:PublicationReferences] conversion-util/dif-publication-reference)
      (update-in [:RelatedUrls] conversion-util/expected-related-urls-for-dif-serf)
      (update :DataLanguage su/capitalize-words)
      ;; DIF 10 required element
      (update-in [:Abstract] #(or % su/not-provided))
      ;; CMR-2716 SpatialKeywords are replaced by LocationKeywords
      (assoc :SpatialKeywords nil)
      js/parse-umm-c))