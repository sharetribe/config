(ns io.aviso.config
  "A system for reading and coercing configuration data.

  Configuration data is in the form of a *set* of files (mostly on the classpath) that follow a naming convention:

      <prefix>-<profile>-<variant>-configuration.<extension>

  The prefix is specific to the application; the list of profiles and variants is provided by the application.

  Currently, the extensions \"yaml\" and \"edn\" are supported.

  The configuration data is read from an appropriate set of such files, and merged together.
  The configuration is then passed through a Schema for validation and coercion.

  Validation helps ensure that simple typos are caught early.
  Coercion helps ensure that the data is both valid and in a format ready to be consumed."
  (:require [schema.coerce :as coerce]
            [schema.utils :as su]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [io.aviso.tracker :as t]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [clojure.java.io :as io]
            [medley.core :as medley]))

(defn- resources
  "For a given resource name on the classpath, provides URLs for all the resources that match, in
  no specific order."
  [name]
  (-> (Thread/currentThread) .getContextClassLoader (.getResources name) enumeration-seq))

(defn- split-env-ref
  [^String env-ref]
  (let [x (.indexOf env-ref ":")]
    (if (pos? x)
      [(subs env-ref 0 x)
       (subs env-ref (inc x))]
      [env-ref])))

(defn- expand-env-vars
  [source env-map]
  (str/replace source
               #"\$\{((?!\$\{).*?)\}"
               (fn [[expansion env-var-reference]]
                 (let [[env-var default-value] (split-env-ref env-var-reference)]
                   (or (get env-map env-var default-value)
                       (throw (ex-info (format "Unable to find expansion for `%s'." expansion)
                                       {:env-var      env-var
                                        :env-map-keys (keys env-map)
                                        :source       source})))))))

(defn- read-single
  "Reads a single configuration file from a URL, expanding environment variables, and
  then parsing the resulting string."
  [url parser env-map]
  (when url
    (t/track
      #(format "Reading configuration from `%s'." url)
      (-> (slurp url)
          (expand-env-vars env-map)
          parser))))

(defn- read-each
  "Read all resources matching a given path into a vector of parsed
  configuration data, ready to merge"
  [path parser env-map]
  (let [urls (resources path)]
    (keep #(read-single % parser env-map) urls)))

(defn- deep-merge
  "Merges maps, recursively. Collections accumulate, otherwise later values override
  earlier values."
  [existing new]
  (cond
    (map? existing) (merge-with deep-merge existing new)
    (coll? existing) (concat existing new)
    :else new))

(def default-extensions
  "The default mapping from file extension to a parser for content from such a file.

  Provides parsers for the \"yaml\" and \"edn\" extensions."
  {"yaml" #(yaml/parse-string % true)
   "edn"  edn/read-string})

(defn default-resource-path
  "Default mapping of a resource path from prefix, profile, variant, and extension.
  A single map is passed, with the following keys:

  :prefix - string, or nil
  : prefix applied to all resource paths

  :profile - keyword
  : profile to add to path, or nil

  :variant - keyword
  : variant to add to the path, or nil

  :extension - string
  : extension (e.g., \"yaml\")

  The result is typically \"prefix-profile-variant-configuration.ext\".

  However, \"-variant\" is omitted when variant is nil, and \"-profile\"
  is omitted when profile is nil."
  [{:keys [prefix profile variant extension]}]
  (str (->> [prefix profile variant "configuration"]
            (remove nil?)
            (map name)
            (str/join "-"))
       "."
       extension))

(def ^{:added "0.1.9"} default-variants
  "The default list of variants. To combination of profile and variant is the main way
  that resource file names are created (combined with a fixed prefix and a supported
  extension).

  The order is important: `'[nil :local]`.

  Typically, a library creates a component or other entity that is represented within
  config as a profile.

  The library provides the nil configuration variant, which forms the defaults.

  The local variant may be used for test-specific overrides, or overrides for a user's
  development (say, to redirect a database connection to a local database), or even
  used in production."
  [nil :local])

(defn- get-parser [^String path extensions]
  (let [dotx      (.lastIndexOf path ".")
        extension (subs path (inc dotx))]
    (or (get extensions extension)
        (throw (ex-info "Unknown extension for configuration file."
                        {:path       path
                         :extensions extensions})))))

(defn merge-value
  "Merges a command-line argument into a map. The command line argument is of the form:

       path=value

   where path is the path to value; it is split at slashes and the key converted to keywords.

   e.g.

       web-server/port=8080

   is equivalent to

       (assoc-in m [:web-server :port] \"8080\")

   "
  {:since "0.1.1"}
  [m arg]
  (cond-let
    [[_ path value] (re-matches #"([^=]+)=(.*)" arg)]

    (not path)
    (throw (IllegalArgumentException. (format "Unable to parse argument `%s'." arg)))

    [keys (map keyword (str/split path #"/"))]

    :else
    (assoc-in m keys value)))

(defn- parse-args
  [args]
  (loop [remaining-args   args
         additional-files []
         overrides        {}]
    (cond-let
      (empty? remaining-args)
      [additional-files overrides]

      [arg (first remaining-args)]

      (= "--load" arg)
      (let [[_ file-name & more-args] remaining-args]
        (recur more-args (conj additional-files file-name) overrides))

      :else
      (recur (rest remaining-args)
             additional-files
             (merge-value overrides arg)))))

(defn assemble-configuration
  "Reads the configuration, as specified by the options.

  Inside each configuration file, the content is scanned for property expansions.

  Expansions allow environment variables, JVM system properties, or explicitly specific properties
  to be substituted into the content of a configuration file, *before* it is parsed.

  Expansions take two forms:

  * `${ENV_VAR}`
  * `${ENV_VAR:default-value}`

  In the former case, a non-nil value for the indicated property or environment variable
  must be available, or an exception is thrown.

  In the later case, a nil value will be replaced with the indicated default value.  e.g. `${HOST:localhost}`.

  The :args option is passed command line arguments (as from a -main function). The arguments
  are used to add further additional files to load, and provide additional overrides.

  Arguments are either \"--load\" followed by a path name, or \"path=value\".

  In the second case, the path and value are as defined by [[merge-value]].

  :prefix (required)
  : The prefix to place at the start of each configuration file read.

  :schemas
  : A seq of schemas; these will be merged to form the full configuration schema.

  :additional-files
  : A seq of absolute file paths that, if they exist, will be loaded last, after all
    normal resources.
    This is typically used to provide an editable (outside the classpath) file for final
    production configuration overrides.

  :args
  : Command line arguments to parse; these yield yet more additional files and
    the last set of overrides.

  :overrides
  : A map of configuration data that is overlayed (using a deep merge)
    on the configuration data read from the files, before validation and coercion.

  :profiles
  : A seq of keywords that identify which profiles should be loaded and in what order.
    The provided profiles are suffixed with a nil profile. The default is an empty list.

  :properties
  : An optional map of properties that may be substituted, just as environment
    variable or System properties can be. Explicit properties have higher precendence than JVM
    system properties, which have higher precendence than environment
    variables; however the convention is that environment variable names
    are all upper case, and properties are all lower case, so actual conflicts
    should not occur.
  : The keys of the properties map are converted to strings via `name`, so they
    may be strings or symbols, or more frequently, keywords.
  : Most often the properties map is used for specific overrides in testing, or
    to expose some bit of configuration that cannot be directly extracted
    from environment variables or JVM system properties.

  :variants
  : The variants searched for, for each profile.
  : [[default-variants]] provides the default list of variants.

  :resource-path
  : A function that builds resource paths from prefix, profile, and extension.
  : The default is [[default-resource-path]], but this could be overridden
    to (for example), use a directory structure to organize configuration files
    rather than splitting up the different components of the name using dashes.

  :extensions
  : Maps from extension (e.g., \"yaml\") to an appropriate parsing function.

  The nil profile is always loaded last.
  Any additional files will then be loaded.

  The contents of each file are deep-merged together; later files override earlier files."
  [{:keys [prefix schemas overrides profiles variants
           resource-path extensions additional-files
           args properties]
    :or   {extensions    default-extensions
           variants      default-variants
           profiles      []
           resource-path default-resource-path}}]
  (let [env-map       (-> (sorted-map)
                          (into (System/getenv))
                          (into (System/getProperties))
                          (into (medley/map-keys name properties)))
        [arg-files arg-overrides] (parse-args args)
        raw           (for [profile (concat profiles [nil])
                            variant variants
                            [extension parser] extensions
                            :let [path (resource-path {:prefix    prefix
                                                       :profile   profile
                                                       :variant   variant
                                                       :extension extension})]]
                        (read-each path parser env-map))
        flattened     (apply concat raw)
        extras        (for [path (concat additional-files arg-files)
                            :let [parser (get-parser path extensions)]]
                        (read-single (io/file path) parser env-map))
        conj'         (fn [x coll] (conj coll x))
        merged        (->> (concat flattened extras)
                           vec
                           (conj' overrides)
                           (conj' arg-overrides)
                           (apply merge-with deep-merge))
        merged-schema (apply merge-with deep-merge schemas)
        coercer       (coerce/coercer merged-schema coerce/string-coercion-matcher)
        config        (coercer merged)]
    (if (su/error? config)
      (throw (ex-info (str "The configuration is not valid: " (-> config su/error-val pr-str))
                      {:schema  merged-schema
                       :config  merged
                       :failure (su/error-val config)}))
      config)))

(defn with-config-schema
  "Adds metadata to the component to define the configuration schema for the component."
  [component schema]
  (vary-meta component assoc ::schema schema))

(defn extract-schemas
  "For a seq of components (the values of a system map),
   extracts the schemas associated via [[with-config-schema]], returning a seq of schemas."
  [components]
  (keep (comp ::schema meta) components))

(defn extend-system-map
  "Uses the system map and options to read the configuration, using [[assemble-configuration]].
  Returns the system map with one extra component, the configuration
  itself (ready to be injected into the other components)."
  {:added "0.1.9"}
  ([system-map options]
   (extend-system-map system-map :configuration options))
  ([system-map configuration-key options]
   (let [schemas       (-> system-map vals extract-schemas)
         configuration (t/track "Reading configuration."
                                (assemble-configuration (assoc options :schemas schemas)))]
     (assoc system-map configuration-key configuration))))