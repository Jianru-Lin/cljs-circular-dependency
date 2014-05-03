(ns circle.core
  #_(:require
      [circle.foo]
      [circle.bar]))

(def pages {:foo {:init circle.foo.init}
            :bar {:init circle.bar.init}})

(defn set-page [page-key]
  (let [init-fn (get-in pages [page-key :init])]
    (init-fn)))

(.addEventListener js/window "load" #(set-page :foo))
