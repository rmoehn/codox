(ns codox.writer.html
  "Documentation writer that outputs HTML."
  (:use [hiccup core page element])
  (:import java.net.URLEncoder)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [codox.utils :as util]))

(def ^:private url-regex
  #"((https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|])")

(defn- add-anchors [text]
  (if text
    (str/replace text url-regex "<a href=\"$1\">$1</a>")))

(defn- ns-filename [namespace]
  (str (:name namespace) ".html"))

(defn- ns-filepath [output-dir namespace]
  (str output-dir "/" (ns-filename namespace)))

(defn- var-id [var]
  (str "var-" (-> var :name str URLEncoder/encode (str/replace "%" "."))))

(defn- var-uri [namespace var]
  (str (ns-filename namespace) "#" (var-id var)))

(defn- var-source-uri [src-dir-uri var anchor-prefix]
  (str src-dir-uri
       (:path var)
       (if anchor-prefix
         (str "#" anchor-prefix (:line var)))))

(defn- split-ns [namespace]
  (str/split (str namespace) #"\."))

(def ns-tree-part
  [:span.tree [:span.top] [:span.bottom]])

(defn- link-to-ns [namespace]
  (let [name (last (split-ns (:name namespace)))]
    (link-to (ns-filename namespace) [:div.inner ns-tree-part [:span (h name)]])))

(defn- link-to-var [namespace var]
  (link-to (var-uri namespace var) [:div.inner [:span (h (:name var))]]))

(defn- namespace-parts [namespace]
  (->> (split-ns namespace)
       (reductions #(str %1 "." %2))
       (map symbol)))

(defn- namespace-hierarchy [namespaces]
  (->> (map :name namespaces)
       (sort)
       (mapcat namespace-parts)
       (distinct)
       (map (juxt identity (comp count split-ns)))
       (partition-all 2 1)
       (map (fn [[[ns d0] [_ d1]]] [ns d0 (= d0 d1)]))))

(defn- index-by [f m]
  (into {} (map (juxt f identity) m)))

(defn- ns-depth [namespace]
  (count (split-ns namespace)))

(defn- namespaces-menu [project & [current]]
  (let [namespaces (:namespaces project)
        ns-map     (index-by :name namespaces)]
    [:div#namespaces.sidebar
     [:h3 (link-to "index.html" [:span.inner "Namespaces"])]
     [:ul
      (for [[name depth branch?] (namespace-hierarchy namespaces)]
        (let [class (str "depth-" depth (if branch? " branch"))]
          (if-let [ns (ns-map name)]
            (let [class (str class (if (= ns current) " current"))]
              [:li {:class class} (link-to-ns ns)])
            (let [name (last (split-ns name))]
              [:li {:class class}
               [:div.no-link [:div.inner ns-tree-part [:span (h name)]]]]))))]]))

(defn- var-links [namespace]
  (unordered-list
    (map (partial link-to-var namespace)
         (sort-by :name (:publics namespace)))))

(defn- vars-menu [namespace]
  [:div#vars.sidebar
   [:h3 (link-to "#top" [:span.inner "Public Vars"])]
   (var-links namespace)])

(def ^{:private true} default-includes
  (list
   [:meta {:charset "UTF-8"}]
   (include-css "css/default.css")
   (include-js "js/jquery.min.js")
   (include-js "js/page_effects.js")))

(defn- project-title [project]
  (str (str/capitalize (:name project)) " "
       (:version project) " API documentation"))

(defn- header [project]
  [:div#header
   [:h2 "Generated by " (link-to "https://github.com/weavejester/codox" "Codox")]
   [:h1 (link-to "index.html" (h (project-title project)))]])

(defn- index-page [project]
  (html5
   [:head
    default-includes
    [:title (h (project-title project))]]
   [:body
    (header project)
    (namespaces-menu project)
    [:div#content.namespace-index
     [:h2 (h (project-title project))]
     [:div.doc (h (:description project))]
     (for [namespace (sort-by :name (:namespaces project))]
       [:div.namespace
        [:h3 (link-to-ns namespace)]
        [:pre.doc (add-anchors (h (util/summary (:doc namespace))))]
        [:div.index
         [:p "Public variables and functions:"]
         (var-links namespace)]])]]))

(defn- var-usage [var]
  (for [arglist (:arglists var)]
    (list* (:name var) arglist)))

(defn- namespace-title [namespace]
  (str (:name namespace) " documentation"))

(defn- namespace-page [project namespace]
  (html5
   [:head
    default-includes
    [:title (h (namespace-title namespace))]]
   [:body
    (header project)
    (namespaces-menu project namespace)
    (vars-menu namespace)
    [:div#content.namespace-docs
     [:h2#top.anchor (h (namespace-title namespace))]
     [:pre.doc (add-anchors (h (:doc namespace)))]
     (for [var (sort-by :name (:publics namespace))]
       [:div.public.anchor {:id (h (var-id var))}
        [:h3 (h (:name var))]
        (if (:macro var) [:h4.macro "macro"])
        (if-let [added (:added var)]
          [:h4.added "added in " added])
        (if-let [deprecated (:deprecated var)]
          [:h4.deprecated
           "deprecated"
           (if (string? deprecated)
             (str " in " deprecated))])
        [:div.usage
         (for [form (var-usage var)]
           [:code (h (pr-str form))])]
        [:pre.doc (add-anchors (h (:doc var)))]
        (if (:src-dir-uri project)
          [:div.src-link
           [:a {:href (var-source-uri (:src-dir-uri project) var
                                      (:src-linenum-anchor-prefix project))}
            "Source"]])])]]))

(defn- copy-resource [output-dir src dest]
  (io/copy (io/input-stream (io/resource src))
           (io/file output-dir dest)))

(defn- mkdirs [output-dir & dirs]
  (doseq [dir dirs]
    (.mkdirs (io/file output-dir dir))))

(defn- write-index [output-dir project]
  (spit (io/file output-dir "index.html") (index-page project)))

(defn- write-namespaces
  [output-dir project]
  (doseq [namespace (:namespaces project)]
    (spit (ns-filepath output-dir namespace)
          (namespace-page project namespace))))

(defn write-docs
  "Take raw documentation info and turn it into formatted HTML."
  [project]
  (doto (:output-dir project "doc")
    (mkdirs "css" "js")
    (copy-resource "codox/css/default.css" "css/default.css")
    (copy-resource "codox/js/jquery.min.js" "js/jquery.min.js")
    (copy-resource "codox/js/page_effects.js" "js/page_effects.js")
    (write-index project)
    (write-namespaces project))
  nil)
