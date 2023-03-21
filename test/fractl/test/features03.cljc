(ns fractl.test.features03
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [fractl.component :as cn]
            [fractl.lang
             :refer [component attribute event
                     entity record relationship
                     dataflow]]
            [fractl.lang.internal :as li]
            [fractl.lang.datetime :as dt]
            [fractl.evaluator :as e]
            #?(:clj [fractl.test.util :as tu :refer [defcomponent]]
               :cljs [fractl.test.util :as tu :refer-macros [defcomponent]])))

(deftest issue-800-rel-path
  (defcomponent :I800
    (entity
     :I800/Company
     {:Name {:type :String
             :identity true}})
    (entity
     :I800/Department
     {:Id {:type :String
           :identity true}
      :No {:type :String
           :indexed true}
      :Location :String})
    (entity
     :I800/Employee
     {:Email {:type :Email
              :identity true}
      :Name :String})
    (entity
     :I800/Assignment
     {:No {:type :Int :identity true}
      :AssignedOn {:type :DateTime :default dt/now}})
    (relationship
     :I800/Section
     {:meta {:contains [:I800/Company :I800/Department
                        :on [:Name :No]]}})
    (relationship
     :I800/WorksFor
     {:meta {:contains [:I800/Department :I800/Employee]}})
    (relationship
     :I800/AssignedTo
     {:meta {:contains [:I800/Employee :I800/Assignment]}})
    (dataflow
     :I800/CreateDepartment
     {:I800/Department
      {:Id :I800/CreateDepartment.Id
       :No :I800/CreateDepartment.No
       :Location :I800/CreateDepartment.Location}
      :-> [{:I800/Section {}}
           {:I800/Company
            {:Name? :I800/CreateDepartment.Company}}]})
    (dataflow
     :I800/UpdateDepartment
     {:I800/Department
      {:? "path://Company/:UpdateDepartment.Company/Section/Department/:UpdateDepartment.Department"
       :Location :I800/UpdateDepartment.Location}})
    (dataflow
     :I800/CreateEmployee
     {:I800/Employee
      {:Email :I800/CreateEmployee.Email
       :Name :I800/CreateEmployee.Name}
      :-> [{:I800/WorksFor {}}
           {:I800/Department? "path://Company/:CreateEmployee.Company/Section/Department/:CreateEmployee.Department"}]}))
  (let [[c1 c2 :as cs] (mapv #(tu/first-result
                               {:I800/Upsert_Company
                                {:Instance
                                 {:I800/Company
                                  {:Name %}}}})
                             ["acme" "zigma"])
        dept-nos ["101" "102" "101"]
        co-names ["acme" "zigma" "zigma"]
        locs ["A111" "A121" "B089"]
        [d1 d2 d3 :as ds] (mapv #(tu/result
                                  {:I800/CreateDepartment
                                   {:Id %1 :No %2 :Location %3 :Company %4}})
                                ["1" "2" "3"] dept-nos locs co-names)
        company? (partial cn/instance-of? :I800/Company)
        dept? (partial cn/instance-of? :I800/Department)
        section-of? #(= %1 (:Company (first (:-> %2))))]
    (is (every? company? cs))
    (is (every? dept? ds))
    (is (every? #(apply section-of? %) [["acme" d1] ["zigma" d2] ["zigma" d3]]))
    (let [emp-names ["a" "b" "c"]
          [e1 e2 e3 :as es] (mapv #(tu/result
                                    {:I800/CreateEmployee
                                     {:Company %1
                                      :Department %2
                                      :Email (str %3 "@" %1 ".com")
                                      :Name %3}})
                                  co-names dept-nos emp-names)
          employee? (partial cn/instance-of? :I800/Employee)
          works-for? #(= %1 (:Department (first (:-> %2))))
          {df :from dt :to} (:transition
                               (tu/first-result
                                {:I800/UpdateDepartment
                                 {:Company "acme" :Department "101" :Location "B786"}}))]
      (is (every? employee? es))
      (is (every? #(apply works-for? %) [["1" e1] ["2" e2] ["3" e3]]))
      (is (cn/same-instance? df d1))
      (is (and (= (:Id dt) (:Id d1)) (= (:Location dt) "B786"))))))

(deftest issue-786-auto-upsert-rels
  (defcomponent :I786
    (entity
     :I786/T
     {:I {:type :Int :identity true}})
    (entity
     :I786/S
     {:J {:type :Int :identity true}})
    (relationship
     :I786/R0
     {:meta {:contains [:I786/T :I786/S]}})
    (entity
     :I786/A
     {:X {:type :Int :identity true}})
    (relationship
     :I786/R1
     {:meta {:contains [:I786/S :I786/A]}})
    (entity
     :I786/B
     {:K {:type :Int :default 1}
      :Y {:type :Int :identity true}})
    (relationship
     :I786/R
     {:meta {:contains [:I786/A :I786/B]}}))
  (let [t (tu/first-result {:I786/Upsert_T
                            {:Instance
                             {:I786/T {:I 1}}}})
        s (tu/first-result {:I786/Upsert_S
                            {:Instance
                             {:I786/S {:J 2}}
                             :T 1}})
        a (tu/result {:I786/Upsert_A
                      {:Instance
                       {:I786/A {:X 100}}
                       :T 1 :S 2}})
        b1 (tu/result {:I786/Upsert_B
                      {:Instance
                       {:I786/B {:Y 20}}
                       :A 100 :T 1 :S 2}})
        b2 (tu/first-result
            {:I786/Lookup_B
             {:A 100 :B 20 :T 1 :S 2}})]
    (is (cn/instance-of? :I786/A a))
    (is (cn/instance-of? :I786/B b1))
    (let [r (first (li/rel-tag b1))]
      (is (cn/instance-of? :I786/R r))
      (is (and (= 100 (:A r)) (= 20 (:B r)))))
    (is (cn/same-instance? b2 (dissoc b1 li/rel-tag)))))

(deftest issue-801-upsert-endpoints-for-between-rels
  (defcomponent :I801
    (entity
     :I801/A
     {:X :Int
      :Id {:type :Int :identity true}})
    (entity
     :I801/B
     {:Y :Int
      :Id {:type :Int :identity true}})
    (relationship
     :I801/R1
     {:meta {:between [:I801/A :I801/B]}})
    (relationship
     :I801/R2
     {:meta {:between [:I801/A :I801/A]}})
    (relationship
     :I801/R3
     {:meta {:between [:I801/A :I801/B :on [:X :Y]]}}))
  (let [[a1 a2] (mapv
                 #(tu/first-result
                   {:I801/Upsert_A
                    {:Instance {:I801/A {:X (* 100 %) :Id %}}}})
                 [1 3])
        b1 (tu/first-result
            {:I801/Upsert_B
             {:Instance {:I801/B {:Y 200 :Id 10}}}})
        a11 (tu/result
             {:I801/Upsert_R1
              {:A 1 :B 10}})
        r1 (first (li/rel-tag a11))]
    (is (cn/same-instance? a1 (dissoc a11 li/rel-tag)))
    (is (cn/instance-of? :I801/R1 r1))
    (is (= (:A r1) 1))
    (is (= (:B r1) 10))
    (let [a11 (tu/result
               {:I801/Upsert_R2
                {:A1 1 :A2 3}})
          r2 (first (li/rel-tag a11))]
      (is (cn/same-instance? a1 (dissoc a11 li/rel-tag)))
      (is (cn/instance-of? :I801/R2 r2))
      (is (= (:A1 r2) 1))
      (is (= (:A2 r2) 3)))
    (let [a11 (tu/result
               {:I801/Upsert_R3
                {:A 3 :B 10}})
          r3 (first (li/rel-tag a11))]
      (is (cn/same-instance? a2 (dissoc a11 li/rel-tag)))
      (is (cn/instance-of? :I801/R3 r3))
      (is (= (:A r3) 300))
      (is (= (:B r3) 200))
      (is (= (:AIdentity r3) 3))
      (is (= (:BIdentity r3) 10)))))

(deftest lookup-all
  (defcomponent :LA
    (entity
     :LA/A
     {:X {:type :Int :identity true}})
    (entity
     :LA/B
     {:Y {:type :Int :identity true}})
    (relationship
     :LA/R
     {:meta {:contains [:LA/A :LA/B]}}))
  (let [as (mapv
            #(tu/first-result
              {:LA/Upsert_A
               {:Instance
                {:LA/A {:X %}}}})
            [1 2])
        bs_1 (mapv #(tu/result
                     {:LA/Upsert_B
                      {:Instance
                       {:LA/B {:Y %}}
                       :A 1}})
                   [10 20 30])
        bs_2 (mapv #(tu/result
                     {:LA/Upsert_B
                      {:Instance
                       {:LA/B {:Y %}}
                       :A 2}})
                   [100 200])]
    (defn- sum [insts attr]
      (reduce (fn [x a] (+ x (attr a))) 0 insts))
    (defn- is-sum-xs [rs]
      (is (= 2 (count rs)))
      (is (= 3 (sum rs :X))))
    (is (every? (partial cn/instance-of? :LA/A) as))
    (is-sum-xs as)
    (defn- check-bs [bs_1 bs_2]
      (is (every? (partial cn/instance-of? :LA/B) bs_1))
      (is (= 3 (count bs_1)))
      (is (= 60 (sum bs_1 :Y)))
      (is (every? (partial cn/instance-of? :LA/B) bs_2))
      (is (= 2 (count bs_2)))
      (is (= 300 (sum bs_2 :Y))))
    (check-bs bs_1 bs_2)
    (let [rs (tu/result {:LA/LookupAll_A {}})]
      (is (every? (partial cn/instance-of? :LA/A) rs))
      (is-sum-xs rs))
    (let [bs_1 (tu/result {:LA/LookupAll_B {:A 1}})
          bs_2 (tu/result {:LA/LookupAll_B {:A 2}})]
      (check-bs bs_1 bs_2))))