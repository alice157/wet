(ns wet.parser-test
  (:require [clojure.test :refer :all]
            [wet.parser :as parser]
            [wet.test-utils :refer [render]]))

(deftest parser-test
  (testing "bare template"
    (is (= "Hello world!" (render "Hello world!"))))

  (testing "assignment"
    (are [expected template] (= expected (render template {"foo" 42}))
      "Hello world!" (str "{% assign bar = \"world\" %}"
                          "Hello {{ bar }}!")
      "Hello world!" (str "{% capture bar %}"
                          "world"
                          "{% endcapture %}"
                          "Hello {{ bar }}!")
      "41 43" (str "{% decrement foo %}"
                   "{{ foo }} "
                   "{% increment foo %}"
                   "{% increment foo %}"
                   "{{ foo }}")))

  (testing "objects"
    (are [expected template] (= expected (render template {"x" "world" "y" 42}))
      "Hello world!" "Hello {{ x }}!"
      "Hello WORLD!" "Hello {{ x | upcase }}!"
      "The meaning of Liquid is 42." "The meaning of Liquid is {{ y }}.")
    (try
      (render "Hello {{ z }}!")
      (catch Exception e
        (is (= (ex-data e) {::parser/undefined-variable "z"})))))

  (testing "iteration"
    (are [expected template]
      (= expected (render template {"xs" (range 1 6)
                                    "friends" ["Chandler"
                                               "Joey"
                                               "Monica"
                                               "Phoebe"
                                               "Rachel"
                                               "Ross"]}))
      "12345" (str "{% for x in xs %}"
                   "{{ x }}"
                   "{% endfor %}")
      "12345" (str "{% for x in (1..5) %}"
                   "{{ x }}"
                   "{% endfor %}")
      "54321" (str "{% for x in (5..1) %}"
                   "{{ x }}"
                   "{% endfor %}")
      "Chandler Joey Monica " (str "{% for f in friends %}"
                                   "{% if f == \"Phoebe\"%}"
                                   "{% break %}"
                                   "{% endif %}"
                                   "{{ f }} "
                                   "{% endfor %}")
      "Chandler Monica Phoebe Ross " (str "{% for f in friends %}"
                                          "{% if f == \"Joey\" or f == \"Rachel\" %}"
                                          "{% continue %}"
                                          "{% endif %}"
                                          "{{ f }} "
                                          "{% endfor %}"))))
