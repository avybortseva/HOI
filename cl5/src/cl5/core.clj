(ns cl5.core
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(defn- new-forks [n]
  (mapv (fn [_] (ref {:holder nil :uses 0})) (range n)))

(defn- neighbouring-forks [forks id]
  [(nth forks id) (nth forks (mod (inc id) (count forks)))])

(defn- worker-stats []
  {:attempts (atom 0) :completed (atom 0) :busy-checks (atom 0)})

(defn- transact! [{:keys [attempts completed]} operation]
  (let [value (dosync
                (swap! attempts inc)
                (operation))]
    (swap! completed inc)
    value))

(defn- try-reserve! [stats forks lease]
  (transact! stats
             (fn []
               (if-let [busy (some (fn [fork] (:holder @fork)) forks)]
                 busy
                 (do
                   (doseq [fork forks]
                     (alter fork assoc :holder lease))
                   nil)))))

(defn- acquire-pair! [stats pair]
  (let [lease (promise)]
    (loop []
      (if-let [busy (try-reserve! stats pair lease)]
        (do
          (swap! (:busy-checks stats) inc)
          @busy
          (recur))
        lease))))

(defn- release! [stats forks lease dined?]
  (transact! stats
             (fn []
               (doseq [fork forks]
                 (alter fork (fn [state]
                               (cond-> (assoc state :holder nil)
                                       dined? (update :uses inc)))))))
  (deliver lease true))

(defn- pause! [ms]
  (when (pos? ms)
    (Thread/sleep (long ms))))

(defn- run-workers [n work]
  (let [ready (CountDownLatch. n)
        start (CountDownLatch. 1)
        jobs (mapv (fn [id]
                     (future
                       (.countDown ready)
                       (.await start)
                       (work id)))
                   (range n))]
    (try
      (.await ready)
      (let [started (System/nanoTime)]
        (.countDown start)
        (let [results (mapv deref jobs)]
          {:workers results
           :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}))
      (finally
        (doseq [job jobs]
          (when-not (future-done? job)
            (future-cancel job)))))))

(defn- summarize [forks {:keys [workers elapsed-ms]}]
  (let [states (mapv deref forks)
        meals (mapv :meals workers)
        total-meals (reduce + meals)
        sum-stat (fn [k] (reduce + (map #(deref (get-in % [:stats k])) workers)))
        attempts (sum-stat :attempts)
        completed (sum-stat :completed)]
    {:elapsed-ms elapsed-ms
     :meals-per-second (/ (* 1000.0 total-meals) elapsed-ms)
     :total-meals total-meals
     :meals-by-philosopher meals
     :fork-uses (mapv :uses states)
     :all-forks-free? (every? (comp nil? :holder) states)
     :transaction-attempts attempts
     :completed-transactions completed
     :stm-restarts (- attempts completed)
     :busy-checks (sum-stat :busy-checks)}))

(defn- check-number! [label value minimum maximum]
  (when-not (and (integer? value) (<= minimum value maximum))
    (throw (IllegalArgumentException.
             (str (name label) " must be an integer from " minimum " to " maximum)))))

(defn run-simulation
  "Each philosopher alternates configurable thinking and dining periods."
  [{:keys [philosophers think-ms dine-ms meals]
    :or {philosophers 5, think-ms 2, dine-ms 4, meals 50}}]
  (check-number! :philosophers philosophers 2 Integer/MAX_VALUE)
  (doseq [[label value] [[:think-ms think-ms] [:dine-ms dine-ms] [:meals meals]]]
    (check-number! label value 0 Long/MAX_VALUE))
  (let [forks (new-forks philosophers)
        run (run-workers philosophers
                         (fn [id]
                           (let [pair (neighbouring-forks forks id)
                                 stats (worker-stats)]
                             (dotimes [_ meals]
                               (pause! think-ms)
                               (let [lease (acquire-pair! stats pair)
                                     dined? (volatile! false)]
                                 (try
                                   (pause! dine-ms)
                                   (vreset! dined? true)
                                   (finally
                                     (release! stats pair lease @dined?)))))
                             {:meals meals :stats stats})))]
    (assoc (summarize forks run)
      :config {:philosophers philosophers :think-ms think-ms
               :dine-ms dine-ms :meals meals})))

(defn- barrier! [^CyclicBarrier barrier]
  (.await barrier 10 TimeUnit/SECONDS))

(defn run-livelock
  "Reproduce a finite prefix of a livelock with a controlled schedule."
  [philosophers rounds]
  (check-number! :philosophers philosophers 2 Integer/MAX_VALUE)
  (check-number! :rounds rounds 1 Long/MAX_VALUE)
  (let [forks (new-forks philosophers)
        left-taken (CyclicBarrier. philosophers)
        right-tried (CyclicBarrier. philosophers)
        run (run-workers philosophers
                         (fn [id]
                           (let [[left right] (neighbouring-forks forks id)
                                 stats (worker-stats)]
                             (dotimes [_ rounds]
                               (let [lease (promise)]
                                 (when (try-reserve! stats [left] lease)
                                   (throw (IllegalStateException. "Left fork should be free")))
                                 (try
                                   (barrier! left-taken)
                                   (when-not (try-reserve! stats [right] lease)
                                     (release! stats [right] lease false)
                                     (throw (IllegalStateException. "Right fork should be occupied")))
                                   (swap! (:busy-checks stats) inc)
                                   (barrier! right-tried)
                                   (finally
                                     (release! stats [left] lease false)))))
                             {:meals 0 :stats stats})))]
    (assoc (summarize forks run)
      :philosophers philosophers
      :rounds-per-philosopher rounds
      :left-reservations (* philosophers rounds))))

(defn- median [values]
  (let [ordered (vec (sort values))]
    (nth ordered (quot (count ordered) 2))))

(defn- run-experiment! []
  (println "Runtime:" {:clojure (clojure-version)
                       :java (System/getProperty "java.version")
                       :os (System/getProperty "os.name")
                       :arch (System/getProperty "os.arch")
                       :processors (.availableProcessors (Runtime/getRuntime))})
  (run-simulation {:philosophers 5 :think-ms 0 :dine-ms 0 :meals 1000})
  (doseq [settings [{:think-ms 2 :dine-ms 4 :meals 60}
                    {:think-ms 0 :dine-ms 0 :meals 4000}]
          n [4 5]]
    (let [config (assoc settings :philosophers n)
          samples (vec (repeatedly 3 #(run-simulation config)))
          metrics (fn [sample]
                    (select-keys sample [:elapsed-ms :meals-per-second :stm-restarts :busy-checks]))]
      (prn {:config config
            :samples (mapv metrics samples)
            :median (into {} (map (fn [k] [k (median (map k samples))])
                                  [:elapsed-ms :meals-per-second :stm-restarts :busy-checks]))
            :total-meals (:total-meals (first samples))
            :fork-uses (:fork-uses (first samples))})))
  (doseq [n [4 5]]
    (prn {:livelock (run-livelock n 100)})))

(defn- parameters [args defaults]
  (when (> (count args) (count defaults))
    (throw (IllegalArgumentException. "Too many arguments")))
  (mapv #(Long/parseLong %) (concat args (drop (count args) defaults))))

(defn -main [& [mode & args]]
  (case (or mode "run")
    "run" (let [[n think dine meals] (parameters args ["5" "2" "4" "50"])]
            (prn (run-simulation {:philosophers n :think-ms think
                                  :dine-ms dine :meals meals})))
    "livelock" (let [[n rounds] (parameters args ["5" "100"])]
                 (prn (run-livelock n rounds)))
    "experiment" (do
                   (when (seq args)
                     (throw (IllegalArgumentException. "experiment takes no arguments")))
                   (run-experiment!))
    (throw (IllegalArgumentException.
             "Usage: run [philosophers think-ms dine-ms meals] | livelock [philosophers rounds] | experiment"))))


(comment
  (-main "run" "4" "2" "4" "60")
  (-main "run" "5" "2" "4" "60")
  (-main "livelock" "5" "100")
  (-main "experiment")
)
