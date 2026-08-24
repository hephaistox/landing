(ns landing.agora.person.display-name
  "Generated display names — the public name every account is given at creation.

  A person's civil identity is never persisted: an OAuth provider hands us a real name, and a
  platform whose subject matter is political, philosophical and religious positions must not let it
  become the byline by default. So the display name is drawn here, server-side, for every provider.

  `display-name-of` is **pure**: the same number yields the same display name, which is what makes it testable
  without I/O and lets a caller draw the number from a `SecureRandom` (a predictable PRNG would tie an
  display name back to the account-creation order). `display-name-key` is the normalized form uniqueness is held
  on — held in code, since folding accents is a domain rule, not a SQL one."
  (:require
   [clojure.string                          :as str]
   [landing.agora.person.display-name-words :as w]
   [landing.agora.text                      :as text]))

(def ^:private default-lang
  "Language a display name is drawn in when the requested one has no vocabulary."
  :fr)

(defn- vocabulary
  "The word lists for `lang` (a keyword or string), falling back to `default-lang`."
  [lang]
  (or (get w/words
           (some-> lang
                   name
                   keyword))
      (get w/words default-lang)))

(defn display-name-count
  "How many distinct display names `lang` can produce — the size of the product of its two lists. The
  cardinality is read from the data, so widening the vocabulary needs no code change."
  [lang]
  (let [{:keys [adjectives nouns]} (vocabulary lang)] (* (count adjectives) (count nouns))))

(defn- cap-first [s] (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn display-name-of
  "The display name numbered `n` in `lang` — « Prémisse Cuivrée », \"Coppered Premise\". Pure and total: `n`
  is taken modulo the vocabulary size, so any number (and any negative one) names a display name, and the
  same number always names the same one. The adjective is inflected to the noun's gender and the two
  slots are ordered as that language reads them; both come from the data, so there is no per-language
  branch here."
  [n lang]
  (let [{:keys [order adjectives nouns]} (vocabulary lang)
        n (mod n (* (count adjectives) (count nouns)))
        [masculine feminine] (nth adjectives (mod n (count adjectives)))
        [word gender] (nth nouns (quot n (count adjectives)))
        slots {:adjective (if (= :f gender) (or feminine masculine) masculine)
               :noun word}]
    (str/join " " (map (comp cap-first slots) order))))

(defn display-name-key
  "The normalized form of a display name, which uniqueness is held on: accents folded, lower-cased,
  runs of whitespace collapsed, ends trimmed. So « Prémisse Cuivrée » and \"premisse  cuivree\" are the
  same claim on a name.

  Most of that the database would do anyway — its collation already reads case, accents and trailing
  spaces as equal — so the folding is a small addition (runs of inner whitespace) rather than the
  reason this exists. **The reason is the column being separate at all**: it is NULL for the
  login-less people the corpus cites, who are not accounts and may be homonyms, so uniqueness applies
  to accounts only. A unique index on the name itself would forbid two « Victor Hugo »."
  [s]
  (-> (text/fold-accents s)
      str/lower-case
      str/trim
      (str/replace #"\s+" " ")))
