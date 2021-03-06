(ns cmr.system-int-test.search.variable.variable-revisions-search-test
  "Integration test for variable all revisions search"
  (:require
   [clojure.test :refer :all]
   [cmr.common.util :refer [are3]]
   [cmr.mock-echo.client.echo-util :as e]
   [cmr.system-int-test.data2.core :as d]
   [cmr.system-int-test.data2.umm-json :as du]
   [cmr.system-int-test.data2.umm-spec-collection :as data-umm-c]
   [cmr.system-int-test.system :as s]
   [cmr.system-int-test.utils.index-util :as index]
   [cmr.system-int-test.utils.ingest-util :as ingest]
   [cmr.system-int-test.utils.metadata-db-util :as metadata-db]
   [cmr.system-int-test.utils.search-util :as search]
   [cmr.system-int-test.utils.variable-util :as variables]
   [cmr.umm-spec.versioning :as umm-version]))

(use-fixtures :each
              (join-fixtures
               [(ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"})
                variables/grant-all-variable-fixture]))

(deftest search-variable-all-revisions
  (let [token (e/login (s/context) "user1")
        coll1 (d/ingest-umm-spec-collection "PROV1"
                                            (data-umm-c/collection 1 {})
                                            {:token token})
        var1-concept (variables/make-variable-concept {:native-id "var1"
                                                       :Name "Variable1"
                                                       :provider-id "PROV1"})
        var1-1 (variables/ingest-variable var1-concept)
        var1-2-tombstone (merge (ingest/delete-concept var1-concept {:token token})
                                var1-concept
                                {:deleted true
                                 :user-id "user1"})
        var1-3 (variables/ingest-variable var1-concept)

        var2-1-concept (variables/make-variable-concept {:native-id "var2"
                                                         :Name "Variable2"
                                                         :LongName "LongName2"
                                                         :provider-id "PROV1"})
        var2-1 (variables/ingest-variable var2-1-concept)
        var2-2-concept (variables/make-variable-concept {:native-id "var2"
                                                         :Name "Variable2-2"
                                                         :LongName "LongName2-2"
                                                         :provider-id "PROV1"})
        var2-2 (variables/ingest-variable var2-2-concept)
        var2-3-tombstone (merge (ingest/delete-concept var2-2-concept {:token token})
                                var2-2-concept
                                {:deleted true
                                 :user-id "user1"})
        var3 (variables/ingest-variable-with-attrs {:native-id "var3"
                                                    :Name "Variable1"
                                                    :LongName "LongName3"
                                                    :provider-id "PROV2"})]
    (index/wait-until-indexed)
    (testing "search variables for all revisions"
      (are3 [variables params]
        (do
          ;; find references with all revisions
          (variables/assert-variable-references-match variables (search/find-refs :variable params))
          ;; search in JSON with all-revisions
          (variables/assert-variable-search variables (variables/search params))
          ;; search in UMM JSON with all-revisions
          (du/assert-variable-umm-jsons-match
           umm-version/current-variable-version variables
           (search/find-concepts-umm-json :variable params)))

        "provider-id all-revisions=false"
        [var1-3]
        {:provider-id "PROV1" :all-revisions false}

        "provider-id all-revisions unspecified"
        [var1-3]
        {:provider-id "PROV1"}

        "provider-id all-revisions=true"
        [var1-1 var1-2-tombstone var1-3 var2-1 var2-2 var2-3-tombstone]
        {:provider-id "PROV1" :all-revisions true}

        "native-id all-revisions=false"
        [var1-3]
        {:native-id "var1" :all-revisions false}

        "native-id all-revisions unspecified"
        [var1-3]
        {:native-id "var1"}

        "native-id all-revisions=true"
        [var1-1 var1-2-tombstone var1-3]
        {:native-id "var1" :all-revisions true}

        "name all-revisions false"
        [var1-3 var3]
        {:name "Variable1" :all-revisions false}

        ;; this test is across providers
        "name all-revisions unspecified"
        [var1-3 var3]
        {:name "Variable1"}

        "name all-revisions true"
        [var1-1 var1-2-tombstone var1-3 var3]
        {:name "Variable1" :all-revisions true}

        "name is updated on revision -- not found without all-revisions true"
        []
        {:name "Variable2"}

        "name is updated on revision -- found with all-revisions true"
        [var2-1]
        {:name "Variable2" :all-revisions true}

        "all-revisions true"
        [var1-1 var1-2-tombstone var1-3 var2-1 var2-2 var2-3-tombstone var3]
        {:all-revisions true}))

    (testing "force delete variable revision"
      ;; force delete a revision
      (metadata-db/force-delete-concept
        (:concept-id var1-1) (:revision-id var1-1))
      ;; force delete a tombstone revision
      (metadata-db/force-delete-concept
        (:concept-id var2-3-tombstone) (:revision-id var2-3-tombstone))
      (index/wait-until-indexed)
      ;; force deleted revisions are no longer in the search result
      (variables/assert-variable-references-match
       [var1-2-tombstone var1-3 var2-1 var2-2 var3]
       (search/find-refs :variable {:all-revisions true}))
      ;; Associate a collection and variable
      (variables/associate-by-concept-ids token
                                          (:concept-id var1-3)
                                          [{:concept-id (:concept-id coll1)}])
      (index/wait-until-indexed)
      (let [expected-associations {:collections [{:concept-id (:concept-id coll1)}]}]
        (testing "associations to collections are captured in the variables all revisions endpoint"
          (variables/assert-variable-associations var1-3 expected-associations
                                                  {:all_revisions true}))
        (let [var1-4 (variables/ingest-variable var1-concept)]
          (index/wait-until-indexed)
          (testing (str "associations are correct in the all revisions endpoint when ingesting a "
                        "new variable revision")
            (variables/assert-variable-associations var1-4 expected-associations
                                                    {:all_revisions true}))
          (testing "force delete cascade to variable association"
            ;; search collections by variable native-id found the collection
            (d/refs-match? [coll1] (search/find-refs :collection {:variable_native_id "var1"}))
            ;; force delete the latest revision of the variable cascade to variable association
            (metadata-db/force-delete-concept (:concept-id var1-4) (:revision-id var1-4))
            (index/wait-until-indexed)
            ;; search collections by variable native-id no longer found the collection
            (d/refs-match? [] (search/find-refs :collection {:variable_native_id "var1"}))))))))

(deftest search-all-revisions-error-cases
  (testing "variable search with all_revisions bad value"
    (let [{:keys [status errors]} (search/find-refs :variable {:all-revisions "foo"})]
      (is (= [400 ["Parameter all_revisions must take value of true, false, or unset, but was [foo]"]]
             [status errors])))))
