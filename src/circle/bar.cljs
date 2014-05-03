(ns circle.bar
  (:require
    [circle.core :refer [set-page]]))

(defn init []
  (aset js/document.body "innerHTML" "On page Bar")
  (js/window.setTimeout #(set-page :foo) 2000))
