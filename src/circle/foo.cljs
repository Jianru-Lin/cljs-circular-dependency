(ns circle.foo
  (:require
    [circle.core :refer [set-page]]))

(defn init []
  (aset js/document.body "innerHTML" "On page Foo")
  (js/window.setTimeout #(set-page :bar) 2000))
