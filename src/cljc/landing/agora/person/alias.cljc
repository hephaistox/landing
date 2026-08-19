(ns landing.agora.person.alias
  "Generated aliases — the public name every account is given at creation.

  A person's civil identity is never persisted: an OAuth provider hands us a real name, and a
  platform whose subject matter is political, philosophical and religious positions must not let it
  become the byline by default. So the alias is drawn here, server-side, for every provider.

  `alias-of` is **pure**: the same number yields the same alias, which is what makes it testable
  without I/O and lets a caller draw the number from a `SecureRandom` (a predictable PRNG would tie an
  alias back to the account-creation order). `alias-key` is the normalized form uniqueness is held
  on — held in code, since folding accents is a domain rule, not a SQL one."
  (:require
   [clojure.string                   :as str]
   [landing.agora.person.alias-words :as w]
   [landing.agora.text               :as text]))

(def ^:private default-lang
  "Language an alias is drawn in when the requested one has no vocabulary."
  :fr)

(defn- vocabulary
  "The word lists for `lang` (a keyword or string), falling back to `default-lang`."
  [lang]
  (or (get w/words
           (some-> lang
                   name
                   keyword))
      (get w/words default-lang)))

(defn alias-count
  "How many distinct aliases `lang` can produce — the size of the product of its two lists. The
  cardinality is read from the data, so widening the vocabulary needs no code change."
  [lang]
  (let [{:keys [adjectives nouns]} (vocabulary lang)] (* (count adjectives) (count nouns))))

(defn- cap-first [s] (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn alias-of
  "The alias numbered `n` in `lang` — « Prémisse Cuivrée », \"Coppered Premise\". Pure and total: `n`
  is taken modulo the vocabulary size, so any number (and any negative one) names an alias, and the
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

(defn alias-key
  "The normalized form of an alias, which uniqueness is held on: accents folded, lower-cased, runs of
  whitespace collapsed, ends trimmed. So « Prémisse Cuivrée » and \"premisse  cuivree\" are the same
  claim on a name."
  [s]
  (-> (text/fold-accents s)
      str/lower-case
      str/trim
      (str/replace #"\s+" " ")))
