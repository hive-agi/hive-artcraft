(ns hive-artcraft.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [hive-artcraft.addon :as a]))

;; hive.artcraft contributes host-tool subcommands (cmds/contribute!), no root
;; tools: `tools` is [] by construction, so an empty set is the expected answer.
(deftest-root-tools root-tools (proto/tools (a/addon-ctor {})) :allow-empty? true)
