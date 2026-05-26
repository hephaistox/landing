(ns landing.article.rivalis)

(def links
  (->> [{:link-id :agile-manifesto
         :url "https://agilemanifesto.org/iso/fr/manifesto.html"}
        {:link-id :henrri
         :url "https://www.henrri.com/"}
        {:link-id :rivalis
         :url "https://www.rivalis.fr/"}]
       (mapv (fn [x] [(:link-id x) x]))
       (into {})))
