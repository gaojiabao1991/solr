/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.common.cloud;

import java.util.Map;

/**
 * Callback registered with {@link ZkStateReader#registerCollectionPropsWatcher(String,
 * CollectionPropsWatcher)} and called whenever the collection properties change.
 */
public interface CollectionPropsWatcher {

  /**
   * Called when the collection properties we are registered against has a change of state
   *
   * <p>Note that, due to the way Zookeeper watchers are implemented, a single call may be the
   * result of several state changes
   *
   * @param collectionProperties the new collection properties
   * @return true if the watcher should be removed
   */
  boolean onStateChanged(Map<String, String> collectionProperties);
}
