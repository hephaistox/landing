(ns landing.article.resume)

(defn resume-body
  [_http-request l]
  (case l
    :en [:article.text [:h1 "Resume"] [:p ""]]
    :fr [:article.text [:h1 "CV"] [:p ""]]))

(def resume-map
  {:title {:en "Architecture"
           :fr "Architecture"}
   :description {:en "Architecture"
                 :fr "Architecture"}
   :handler resume-body})
