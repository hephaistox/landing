(ns landing.article.who-are-we)

(def links
  (->> [{:link-id :resume
         :url "/cv_caumond.pdf"}]
       (mapv (fn [link] [(:link-id link) link]))
       (into {})))

