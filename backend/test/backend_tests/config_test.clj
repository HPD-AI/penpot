;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.config-test
  (:require
   [app.config :as cf]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/deftest secret-key-file
  (let [path (fs/create-tempfile
              :prefix "penpot-secret-"
              :suffix ".txt")]
    (try
      (spit path "secret-from-file\n")
      (t/is (= {:secret-key "secret-from-file"}
               (#'cf/resolve-secret-key-file
                {:secret-key-file (str path)})))
      (finally
        (fs/delete path)))))

(t/deftest conflicting-secret-key-sources
  (let [path (fs/create-tempfile
              :prefix "penpot-secret-"
              :suffix ".txt")]
    (try
      (spit path "secret-from-file\n")
      (t/is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"cannot both be set"
             (#'cf/prepare-file-backed-config
              {}
              {:secret-key "secret-from-environment"
               :secret-key-file (str path)})))
      (finally
        (fs/delete path)))))

(t/deftest secret-key-file-rejects-symlinks
  (let [target (fs/create-tempfile
                :prefix "penpot-secret-"
                :suffix ".txt")
        link   (fs/path (str target ".link"))]
    (try
      (spit target "secret-from-file\n")
      (java.nio.file.Files/createSymbolicLink
       link
       target
       (make-array java.nio.file.attribute.FileAttribute 0))
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (#'cf/resolve-secret-key-file
                      {:secret-key-file (str link)})))
      (finally
        (java.nio.file.Files/deleteIfExists link)
        (fs/delete target)))))

(t/deftest public-uri-file-overrides-only-the-default
  (let [path (fs/create-tempfile
              :prefix "penpot-public-uri-"
              :suffix ".txt")]
    (try
      (spit path "https://penpot.apps.hpd.localhost/\n")
      (t/is (= "https://penpot.apps.hpd.localhost/"
               (-> (#'cf/prepare-file-backed-config
                    {:public-uri "http://localhost:3449"}
                    {:public-uri-file (str path)})
                   (#'cf/resolve-public-uri-file)
                   :public-uri)))
      (t/is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"cannot both be set"
             (#'cf/prepare-file-backed-config
              {}
              {:public-uri "https://other.example/"
               :public-uri-file (str path)})))
      (finally
        (fs/delete path)))))

(t/deftest database-password-file
  (let [path (fs/create-tempfile
              :prefix "penpot-database-password-"
              :suffix ".txt")]
    (try
      (spit path "database-password\n")
      (t/is (= "database-password"
               (-> (#'cf/prepare-file-backed-config
                    {:database-password "default"}
                    {:database-password-file (str path)})
                   (#'cf/resolve-database-password-file)
                   :database-password)))
      (finally
        (fs/delete path)))))
