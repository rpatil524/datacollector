/*
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.processor.http;

import com.google.common.collect.ImmutableList;
import com.streamsets.pipeline.api.Config;
import com.streamsets.pipeline.lib.http.AuthenticationType;
import com.streamsets.pipeline.lib.http.HttpProxyConfigBean;
import com.streamsets.pipeline.lib.http.OAuthConfigBean;
import com.streamsets.pipeline.lib.http.PasswordAuthConfigBean;
import com.streamsets.pipeline.lib.http.SslConfigBean;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class TestHttpProcessorUpgrader {
  private static final Logger LOG = LoggerFactory.getLogger(TestHttpProcessorUpgrader.class);

  @Test
  public void testV1ToV2() throws Exception {
    List<Config> configs = new ArrayList<>();

    configs.add(new Config("conf.requestTimeoutMillis", 1000));
    configs.add(new Config("conf.numThreads", 10));
    configs.add(new Config("conf.authType", AuthenticationType.NONE));
    configs.add(new Config("conf.oauth", new OAuthConfigBean()));
    configs.add(new Config("conf.basicAuth", new PasswordAuthConfigBean()));
    configs.add(new Config("conf.useProxy", false));
    configs.add(new Config("conf.proxy", new HttpProxyConfigBean()));
    configs.add(new Config("conf.sslConfig", new SslConfigBean()));

    HttpProcessorUpgrader upgrader = new HttpProcessorUpgrader();

    upgrader.upgrade("a", "b", "c", 1, 2, configs);

    Map<String, Object> configValues = getConfigsAsMap(configs);
    List<String> movedConfigs = ImmutableList.of(
        "conf.client.requestTimeoutMillis",
        "conf.client.numThreads",
        "conf.client.authType",
        "conf.client.oauth",
        "conf.client.basicAuth",
        "conf.client.useProxy",
        "conf.client.proxy",
        "conf.client.sslConfig"
    );

    for (String config : movedConfigs) {
      boolean isPresent = configValues.containsKey(config);
      LOG.debug("{} is present: {}", config, isPresent);
      assertTrue(isPresent);
    }
  }

  private static Map<String, Object> getConfigsAsMap(List<Config> configs) {
    HashMap<String, Object> map = new HashMap<>();
    for (Config c : configs) {
      map.put(c.getName(), c.getValue());
    }
    return map;
  }
}
